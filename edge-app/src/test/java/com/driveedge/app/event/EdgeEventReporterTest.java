package com.driveedge.app.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.driveedge.event.center.EdgeEvent;
import com.driveedge.event.center.EdgeEventStore;
import com.driveedge.event.center.EventCenter;
import com.driveedge.event.center.EventCenterConfig;
import com.driveedge.event.center.UploadStatus;
import com.driveedge.risk.engine.RiskEventCandidate;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.risk.engine.RiskType;
import com.driveedge.risk.engine.TriggerReason;
import com.driveedge.storage.DeviceConfigRow;
import com.driveedge.storage.EdgeEventRow;
import com.driveedge.storage.StorageCenter;
import com.driveedge.storage.StorageConfig;
import com.driveedge.storage.UploadAttemptResult;
import com.driveedge.storage.UploadFailureClass;
import com.driveedge.uploader.EventUploader;
import com.driveedge.uploader.EventsApiTransport;
import com.driveedge.uploader.TransportException;
import com.driveedge.uploader.TransportResponse;
import com.driveedge.uploader.UploaderConfig;
import com.driveedge.uploader.UploadFailureCategory;

import org.junit.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class EdgeEventReporterTest {
  private static final String TEST_SERVER_BASE_URL = "http://127.0.0.1:8080";

  @Test
  public void offlineBacklogDoesNotUploadUntilNetworkRecovers() throws Exception {
    AtomicBoolean networkAvailable = new AtomicBoolean(false);
    InMemoryReporterQueueStore store = new InMemoryReporterQueueStore();
    RecordingTransport transport = new RecordingTransport();
    TestHarness harness = createHarness(store, transport, networkAvailable, defaultStorageConfig());

    try {
      harness.reporter.reportRiskCandidate(candidate("offline-1", 1_000L));
      harness.reporter.reportRiskCandidate(candidate("offline-2", 2_000L));
      harness.reporter.reportRiskCandidate(candidate("offline-3", 3_000L));

      awaitCondition(() -> store.countQueuedEvents(System.currentTimeMillis()) == 3, "expected offline queue buildup");

      assertEquals(0, transport.callCount.get());
      assertTrue(harness.statusLines.stream().anyMatch(line -> line.contains("等待网络恢复")));
    } finally {
      harness.close();
    }
  }

  @Test
  public void networkRecoveryTriggersImmediateUpload() throws Exception {
    AtomicBoolean networkAvailable = new AtomicBoolean(false);
    InMemoryReporterQueueStore store = new InMemoryReporterQueueStore();
    RecordingTransport transport = new RecordingTransport();
    TestHarness harness = createHarness(store, transport, networkAvailable, defaultStorageConfig());

    try {
      harness.reporter.reportRiskCandidate(candidate("recovery-1", 5_000L));
      awaitCondition(() -> store.countQueuedEvents(System.currentTimeMillis()) == 1, "expected queued event while offline");
      assertEquals(0, transport.callCount.get());

      networkAvailable.set(true);
      harness.reporter.onNetworkAvailable();

      awaitCondition(() -> transport.callCount.get() == 1, "expected upload after network recovery");
      awaitCondition(() -> store.countQueuedEvents(System.currentTimeMillis()) == 0, "expected queue drain after recovery");

      EdgeEventRow row = store.findByPrefix("evt_VEH-001_");
      assertEquals(UploadStatus.SUCCESS, row.getUploadStatus());
      assertTrue(
        harness.statusLines.stream().anyMatch(line -> line.contains("服务器可达"))
      );
    } finally {
      harness.close();
    }
  }

  @Test
  public void networkRecoveryDrainsRetriedBacklogInAdaptiveBatches() throws Exception {
    AtomicBoolean networkAvailable = new AtomicBoolean(true);
    InMemoryReporterQueueStore store = new InMemoryReporterQueueStore();
    RecordingTransport transport = new RecordingTransport();
    StorageConfig storageConfig =
      new StorageConfig(
        3,
        50,
        100,
        new com.driveedge.storage.RetryBackoffPolicy(Collections.singletonList(0L), 0L, 0L)
      );
    TestHarness harness = createHarness(store, transport, networkAvailable, storageConfig);

    try {
      for (int index = 0; index < 20; index++) {
        String eventId = "evt-backlog-" + index;
        long nowMs = 10_000L + index;
        EdgeEvent event = edgeEvent(eventId, nowMs);
        harness.storageCenter.onEdgeEvent(event, nowMs);
        harness.storageCenter.claimUploadBatch(1, nowMs + 100L);
        harness.storageCenter.onUploadResult(
          new UploadAttemptResult(eventId, -1, "timeout", null, UploadFailureClass.TIMEOUT),
          nowMs + 200L
        );
      }

      harness.reporter.onNetworkAvailable();

      awaitCondition(() -> transport.callCount.get() == 20, "expected backlog upload attempts");
      awaitCondition(() -> store.countQueuedEvents(System.currentTimeMillis()) == 0, "expected drained backlog");

      assertTrue("expected retry backlog to use batched claims", store.maxRequestedLimit.get() >= 8);
    } finally {
      harness.close();
    }
  }

  private static TestHarness createHarness(
    InMemoryReporterQueueStore store,
    RecordingTransport transport,
    AtomicBoolean networkAvailable,
    StorageConfig storageConfig
  ) {
    StorageCenter storageCenter = new StorageCenter(store, store, storageConfig, Clock.systemUTC());
    EdgeEventStore eventStore = event -> storageCenter.onEdgeEvent(event, System.currentTimeMillis());
    EventCenter eventCenter =
      new EventCenter(new EventCenterConfig("DEV-001", "VEH-001", null, "fleet-01", "drv-01", null, null, "algo-v1", 0L), eventStore);
    EventUploader uploader = new EventUploader(new UploaderConfig(TEST_SERVER_BASE_URL, "DEV-001", "token-001"), transport);
    ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    List<String> statusLines = Collections.synchronizedList(new ArrayList<>());
    EdgeEventReporter reporter =
      new EdgeEventReporter(
        store,
        storageCenter,
        eventCenter,
        uploader,
        uploadExecutor,
        retryScheduler,
        statusLines::add,
        networkAvailable::get
      );
    return new TestHarness(reporter, storageCenter, transport, statusLines);
  }

  private static StorageConfig defaultStorageConfig() {
    return new StorageConfig();
  }

  private static RiskEventCandidate candidate(String ignoredSuffix, long eventTimeMs) {
    return new RiskEventCandidate(
      eventTimeMs - 3_000L,
      eventTimeMs,
      0.15,
      0.82,
      RiskLevel.HIGH,
      RiskType.DISTRACTION,
      false,
      true,
      true,
      Collections.singleton(TriggerReason.DISTRACTION_GAZE_OFFSET_SUSTAINED)
    );
  }

  private static EdgeEvent edgeEvent(String eventId, long createdAtMs) {
    return new EdgeEvent(
      eventId,
      "DEV-001",
      null,
      "fleet-01",
      "VEH-001",
      "drv-01",
      null,
      null,
      "2026-04-25T10:00:00Z",
      0.10,
      0.91,
      RiskLevel.HIGH,
      RiskType.DISTRACTION,
      Collections.singleton(TriggerReason.DISTRACTION_HEAD_DOWN_SUSTAINED),
      "algo-v1",
      UploadStatus.PENDING,
      createdAtMs - 3_000L,
      createdAtMs,
      createdAtMs
    );
  }

  private static void awaitCondition(CheckedBooleanSupplier condition, String message) throws Exception {
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError(message);
  }

  @FunctionalInterface
  private interface CheckedBooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }

  private static final class TestHarness implements AutoCloseable {
    final EdgeEventReporter reporter;
    final StorageCenter storageCenter;
    final RecordingTransport transport;
    final List<String> statusLines;

    private TestHarness(
      EdgeEventReporter reporter,
      StorageCenter storageCenter,
      RecordingTransport transport,
      List<String> statusLines
    ) {
      this.reporter = reporter;
      this.storageCenter = storageCenter;
      this.transport = transport;
      this.statusLines = statusLines;
    }

    @Override
    public void close() {
      reporter.close();
    }
  }

  private static final class RecordingTransport implements EventsApiTransport {
    final AtomicInteger callCount = new AtomicInteger();
    final Queue<Object> scriptedOutcomes = new ConcurrentLinkedQueue<>();

    @Override
    public TransportResponse postEvent(
      String endpointUrl,
      String deviceCode,
      String deviceToken,
      String eventId,
      String idempotencyHeaderName,
      String eventIdHeaderName,
      String requestBody,
      java.time.Duration timeout
    ) {
      callCount.incrementAndGet();
      Object outcome = scriptedOutcomes.poll();
      if (outcome instanceof RuntimeException) {
        throw (RuntimeException) outcome;
      }
      if (outcome instanceof TransportResponse) {
        return (TransportResponse) outcome;
      }
      return new TransportResponse(200, "{\"code\":0,\"message\":\"ok\",\"traceId\":\"trace-" + eventId + "\"}");
    }

    void enqueueTimeout(String message) {
      scriptedOutcomes.add(new TransportException(message, UploadFailureCategory.TIMEOUT, null));
    }
  }

  private static final class InMemoryReporterQueueStore implements EdgeEventReporter.ReporterQueueStore {
    private final Map<String, EdgeEventRow> edgeEvents = new LinkedHashMap<>();
    private final Map<String, DeviceConfigRow> deviceConfigs = new LinkedHashMap<>();
    final AtomicInteger maxRequestedLimit = new AtomicInteger();

    @Override
    public synchronized void upsert(EdgeEventRow row) {
      edgeEvents.put(row.getEventId(), row);
    }

    @Override
    public synchronized EdgeEventRow getByEventId(String eventId) {
      return edgeEvents.get(eventId);
    }

    @Override
    public synchronized void update(EdgeEventRow row) {
      edgeEvents.put(row.getEventId(), row);
    }

    @Override
    public synchronized List<EdgeEventRow> listReadyForUpload(long nowMs, int limit) {
      maxRequestedLimit.accumulateAndGet(limit, Math::max);
      List<EdgeEventRow> readyRows = new ArrayList<>();
      for (EdgeEventRow row : edgeEvents.values()) {
        if (isReady(row, nowMs)) {
          readyRows.add(row);
        }
      }
      readyRows.sort(
        Comparator
          .comparingInt(InMemoryReporterQueueStore::priorityOf)
          .thenComparingLong(row -> row.getNextRetryAtMs() == null ? Long.MIN_VALUE : row.getNextRetryAtMs())
          .thenComparingLong(row -> row.getLastAttemptAtMs() == null ? Long.MIN_VALUE : row.getLastAttemptAtMs())
          .thenComparingLong(row -> row.getEvent().getCreatedAtMs())
      );
      return readyRows.size() > limit ? new ArrayList<>(readyRows.subList(0, limit)) : readyRows;
    }

    @Override
    public synchronized void upsert(DeviceConfigRow row) {
      deviceConfigs.put(row.getDeviceId(), row);
    }

    @Override
    public synchronized DeviceConfigRow getByDeviceId(String deviceId) {
      return deviceConfigs.get(deviceId);
    }

    @Override
    public synchronized int countQueuedEvents(long nowMs) {
      int count = 0;
      for (EdgeEventRow row : edgeEvents.values()) {
        UploadStatus status = row.getUploadStatus();
        if (status == UploadStatus.PENDING || status == UploadStatus.RETRY_WAIT || status == UploadStatus.SENDING) {
          count++;
        }
      }
      return count;
    }

    @Override
    public synchronized boolean hasReadyEvents(long nowMs) {
      for (EdgeEventRow row : edgeEvents.values()) {
        if (isReady(row, nowMs)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public synchronized Long nextRetryAtMs(long nowMs) {
      Long nextRetryAtMs = null;
      for (EdgeEventRow row : edgeEvents.values()) {
        if (row.getUploadStatus() != UploadStatus.RETRY_WAIT || row.getNextRetryAtMs() == null) {
          continue;
        }
        if (nextRetryAtMs == null || row.getNextRetryAtMs() < nextRetryAtMs) {
          nextRetryAtMs = row.getNextRetryAtMs();
        }
      }
      return nextRetryAtMs;
    }

    synchronized EdgeEventRow findByPrefix(String eventIdPrefix) {
      for (EdgeEventRow row : edgeEvents.values()) {
        if (row.getEventId().startsWith(eventIdPrefix)) {
          return row;
        }
      }
      throw new AssertionError("missing event with prefix " + eventIdPrefix);
    }

    private static boolean isReady(EdgeEventRow row, long nowMs) {
      if (row.getUploadStatus() == UploadStatus.PENDING) {
        return true;
      }
      return row.getUploadStatus() == UploadStatus.RETRY_WAIT
        && row.getNextRetryAtMs() != null
        && row.getNextRetryAtMs() <= nowMs;
    }

    private static int priorityOf(EdgeEventRow row) {
      return row.getUploadStatus() == UploadStatus.RETRY_WAIT ? 0 : 1;
    }
  }
}
