package com.driveedge.app.event;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.driveedge.app.BuildConfig;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeLocalContext;
import com.driveedge.app.fatigue.LocalFatigueAnalyzer;
import com.driveedge.event.center.EventDebouncer;
import com.driveedge.event.center.EdgeEvent;
import com.driveedge.event.center.EventIdGenerator;
import com.driveedge.event.center.EdgeEventStore;
import com.driveedge.event.center.EventCenter;
import com.driveedge.event.center.EventCenterConfig;
import com.driveedge.event.center.UploadStatus;
import com.driveedge.risk.engine.RiskEventCandidate;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.risk.engine.RiskType;
import com.driveedge.risk.engine.TriggerReason;
import com.driveedge.storage.DeviceConfigDao;
import com.driveedge.storage.DeviceConfigRow;
import com.driveedge.storage.EdgeEventDao;
import com.driveedge.storage.EdgeEventRow;
import com.driveedge.storage.StorageCenter;
import com.driveedge.storage.StorageConfig;
import com.driveedge.storage.UploadFailureClass;
import com.driveedge.storage.UploadAttemptResult;
import com.driveedge.storage.UploadQueueItem;
import com.driveedge.uploader.EventUploader;
import com.driveedge.uploader.UploadReceipt;
import com.driveedge.uploader.UploaderConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EdgeEventReporter implements AutoCloseable {
  private static final int MAX_PENDING_SCAN = 512;
  private static final int UPLOAD_BATCH_SIZE = 8;
  private static final int MAX_UPLOAD_BATCH_SIZE = 24;

  @NonNull
  private final Context appContext;
  @NonNull
  private final ReporterQueueStore queueStore;
  @Nullable
  private final EdgeContextStore edgeContextStore;
  @NonNull
  private final StorageCenter storageCenter;
  @NonNull
  private final EventDebouncer eventDebouncer;
  @Nullable
  private final EventCenter injectedEventCenter;
  @Nullable
  private final EventUploader injectedEventUploader;
  @NonNull
  private final ExecutorService uploadExecutor;
  @NonNull
  private final ScheduledExecutorService retryScheduler;
  @NonNull
  private final AtomicBoolean uploadLoopRunning = new AtomicBoolean(false);
  @NonNull
  private final StatusListener statusListener;
  @NonNull
  private final BooleanSupplier networkChecker;
  @Nullable
  private final ConnectivityManager connectivityManager;
  @Nullable
  private final ConnectivityManager.NetworkCallback networkCallback;
  @Nullable
  private ScheduledFuture<?> retryFuture;
  private long scheduledRetryAtMs = Long.MAX_VALUE;
  private volatile boolean closed = false;
  private final boolean directUploadMode;
  @NonNull
  private volatile String lastStatusLine = "事件上报：待触发";

  public EdgeEventReporter(@NonNull Context context, @NonNull StatusListener statusListener) {
    Context appContext = context.getApplicationContext();
    ReporterQueueStore store;
    try {
      store = createQueueStore(appContext);
    } catch (Exception error) {
      store = new PrefsQueueStore();
      updateStatus("事件上报：本地队列初始化失败，已切换内存队列");
    }
    StorageCenter storageCenter = new StorageCenter(store, store, new StorageConfig(), Clock.systemUTC());
    EdgeContextStore edgeContextStore = new EdgeContextStore(appContext);

    ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    ConnectivityManager.NetworkCallback callback = null;
    if (manager != null) {
      callback =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(@NonNull Network network) {
            handleNetworkAvailable();
          }
        };
      try {
        manager.registerDefaultNetworkCallback(callback);
      } catch (Exception ignored) {
        updateStatus("事件上报：网络监听不可用，改为定时重试");
      }
    }
    this.appContext = appContext;
    this.queueStore = store;
    this.edgeContextStore = edgeContextStore;
    this.storageCenter = storageCenter;
    this.eventDebouncer = new EventDebouncer(8_000L);
    this.injectedEventCenter = null;
    this.injectedEventUploader = null;
    this.uploadExecutor = Executors.newSingleThreadExecutor();
    this.retryScheduler = Executors.newSingleThreadScheduledExecutor();
    this.statusListener = statusListener;
    this.networkChecker = this::isNetworkAvailableDefault;
    this.connectivityManager = manager;
    this.networkCallback = callback;
    this.directUploadMode = false;
    EdgeEventUploadWorker.ensurePeriodic(appContext);
    EdgeEventUploadWorker.enqueueImmediate(appContext);
  }

  EdgeEventReporter(
    @NonNull ReporterQueueStore queueStore,
    @NonNull StorageCenter storageCenter,
    @NonNull EventCenter eventCenter,
    @NonNull EventUploader eventUploader,
    @NonNull ExecutorService uploadExecutor,
    @NonNull ScheduledExecutorService retryScheduler,
    @NonNull StatusListener statusListener,
    @NonNull BooleanSupplier networkChecker
  ) {
    this.appContext = null;
    this.queueStore = queueStore;
    this.edgeContextStore = null;
    this.storageCenter = storageCenter;
    this.eventDebouncer = new EventDebouncer(8_000L);
    this.injectedEventCenter = eventCenter;
    this.injectedEventUploader = eventUploader;
    this.statusListener = statusListener;
    this.networkChecker = networkChecker;
    this.connectivityManager = null;
    this.networkCallback = null;
    this.uploadExecutor = uploadExecutor;
    this.retryScheduler = retryScheduler;
    this.directUploadMode = true;
    pumpUploads();
  }

  EdgeEventReporter(
    @NonNull ReporterQueueStore queueStore,
    @NonNull StorageCenter storageCenter,
    @NonNull EventDebouncer eventDebouncer,
    @NonNull ExecutorService uploadExecutor,
    @NonNull ScheduledExecutorService retryScheduler,
    @NonNull StatusListener statusListener,
    @NonNull BooleanSupplier networkChecker
  ) {
    this.appContext = null;
    this.queueStore = queueStore;
    this.edgeContextStore = null;
    this.storageCenter = storageCenter;
    this.eventDebouncer = eventDebouncer;
    this.injectedEventCenter = null;
    this.injectedEventUploader = null;
    this.statusListener = statusListener;
    this.networkChecker = networkChecker;
    this.connectivityManager = null;
    this.networkCallback = null;
    this.uploadExecutor = uploadExecutor;
    this.retryScheduler = retryScheduler;
    this.directUploadMode = true;
    pumpUploads();
  }

  public void reportFatigueResult(@NonNull LocalFatigueAnalyzer.Result fatigue, long eventTimeMs) {
    if (closed || !fatigue.drowsy) {
      return;
    }
    reportRiskCandidate(toFatigueCandidate(fatigue, eventTimeMs));
  }

  public void reportRiskCandidate(@NonNull RiskEventCandidate candidate) {
    if (closed || !candidate.getShouldTrigger()) {
      return;
    }

    EventCenter eventCenter = injectedEventCenter != null ? injectedEventCenter : createEventCenter();
    if (eventCenter == null) {
      updateStatus("事件上报：设备上下文未就绪");
      return;
    }
    EdgeEvent event = eventCenter.process(candidate);
    if (event == null) {
      updateQueuedStatus("事件上报：窗口内去抖");
      return;
    }

    updateQueuedStatus("事件上报：已入队 " + event.getEventId());
    triggerUpload();
  }

  @NonNull
  public String getLastStatusLine() {
    return lastStatusLine;
  }

  @Override
  public void close() {
    closed = true;
    synchronized (this) {
      if (retryFuture != null) {
        retryFuture.cancel(false);
        retryFuture = null;
      }
    }
    if (connectivityManager != null && networkCallback != null) {
      try {
        connectivityManager.unregisterNetworkCallback(networkCallback);
      } catch (Exception ignored) {
      }
    }
    uploadExecutor.shutdownNow();
    retryScheduler.shutdownNow();
  }

  private void triggerUpload() {
    pumpUploads();
    if (directUploadMode) {
      return;
    }
    Context context = appContext;
    if (context != null) {
      EdgeEventUploadWorker.enqueueImmediate(context);
    }
  }

  private void pumpUploads() {
    if (closed) {
      return;
    }
    if (!uploadLoopRunning.compareAndSet(false, true)) {
      return;
    }
    uploadExecutor.execute(this::drainUploads);
  }

  private void drainUploads() {
    try {
      while (!closed) {
        long nowMs = System.currentTimeMillis();
        if (!isNetworkAvailable()) {
          updateWaitingForNetwork();
          scheduleNextRetryIfNeeded(nowMs);
          return;
        }

        List<UploadQueueItem> batch = storageCenter.claimUploadBatch(resolveBatchSize(nowMs), nowMs);
        if (batch.isEmpty()) {
          scheduleNextRetryIfNeeded(nowMs);
          if (queueStore.countQueuedEvents(nowMs) == 0) {
            updateStatus("事件上报：队列已清空");
          }
          return;
        }

        for (UploadQueueItem item : batch) {
          EventUploader eventUploader = injectedEventUploader != null ? injectedEventUploader : createEventUploader(appContext, edgeContextStore);
          if (eventUploader == null) {
            updateStatus("事件上报：设备未激活，等待上下文同步");
            return;
          }
          UploadReceipt receipt = eventUploader.upload(item.getEvent());
          EdgeEventRow row = storageCenter.onUploadResult(
            new UploadAttemptResult(
              item.getEvent().getEventId(),
              receipt.getCode(),
              firstNonBlank(receipt.getTransportError(), receipt.getMessage(), null),
              receipt.getTraceId(),
              toFailureClass(receipt)
            ),
            System.currentTimeMillis()
          );
          updateStatusForRow(row, receipt);
        }
      }
    } finally {
      uploadLoopRunning.set(false);
      long nowMs = System.currentTimeMillis();
      if (!closed && isNetworkAvailable() && queueStore.hasReadyEvents(nowMs)) {
        pumpUploads();
      }
    }
  }

  private void updateStatusForRow(@Nullable EdgeEventRow row, @NonNull UploadReceipt receipt) {
    if (row == null) {
      updateStatus("事件上报：状态回写失败 eventId=" + receipt.getEventId());
      return;
    }
    String httpStatus = receipt.getHttpStatus() == null ? "-" : String.valueOf(receipt.getHttpStatus());
    String traceId = firstNonBlank(receipt.getTraceId(), "-", "-");
    switch (row.getUploadStatus()) {
      case SUCCESS:
        if (receipt.getCode() == 40002) {
          updateStatus(
            "事件上报：服务器可达，设备鉴权成功（事件重复）"
              + " http=" + httpStatus
              + " code=" + receipt.getCode()
              + " trace=" + traceId
              + " queue=" + queueStore.countQueuedEvents(System.currentTimeMillis())
          );
        } else {
          updateStatus(
            "事件上报：服务器可达，设备鉴权成功"
              + " http=" + httpStatus
              + " code=" + receipt.getCode()
              + " trace=" + traceId
              + " queue=" + queueStore.countQueuedEvents(System.currentTimeMillis())
          );
        }
        break;
      case RETRY_WAIT:
        long waitMs = Math.max(0L, (row.getNextRetryAtMs() == null ? 0L : row.getNextRetryAtMs()) - System.currentTimeMillis());
        updateStatus(
          "事件上报：服务器不可达或请求失败"
            + " http=" + httpStatus
            + " code=" + receipt.getCode()
            + " class=" + row.getFailureClass().name()
            + " after=" + waitMs + "ms"
            + " queue=" + queueStore.countQueuedEvents(System.currentTimeMillis())
        );
        break;
      case FAILED_FINAL:
        if (receipt.getCode() == 40101) {
          updateStatus(
            "事件上报：服务器可达，但设备鉴权失败"
              + " http=" + httpStatus
              + " code=" + receipt.getCode()
              + " trace=" + traceId
          );
        } else {
          updateStatus(
            "事件上报：服务器可达，但请求被拒绝"
              + " http=" + httpStatus
              + " code=" + receipt.getCode()
              + " trace=" + traceId
              + " msg=" + firstNonBlank(row.getLastErrorMessage(), "unknown", "unknown")
          );
        }
        break;
      default:
        updateStatus("事件上报：状态=" + row.getUploadStatus().name());
        break;
    }
  }

  private void scheduleNextRetryIfNeeded(long nowMs) {
    Long nextRetryAtMs = queueStore.nextRetryAtMs(nowMs);
    synchronized (this) {
      if (retryFuture != null && (nextRetryAtMs == null || nextRetryAtMs >= scheduledRetryAtMs)) {
        return;
      }
      if (retryFuture != null) {
        retryFuture.cancel(false);
        retryFuture = null;
        scheduledRetryAtMs = Long.MAX_VALUE;
      }
      if (nextRetryAtMs == null) {
        return;
      }
      long delayMs = Math.max(0L, nextRetryAtMs - nowMs);
      scheduledRetryAtMs = nextRetryAtMs;
      retryFuture =
        retryScheduler.schedule(() -> {
          synchronized (EdgeEventReporter.this) {
            retryFuture = null;
            scheduledRetryAtMs = Long.MAX_VALUE;
          }
          pumpUploads();
        }, delayMs, TimeUnit.MILLISECONDS);
    }
  }

  private void updateWaitingForNetwork() {
    updateStatus("事件上报：等待网络恢复 queue=" + queueStore.countQueuedEvents(System.currentTimeMillis()));
  }

  private void updateQueuedStatus(@NonNull String prefix) {
    updateStatus(prefix + " queue=" + queueStore.countQueuedEvents(System.currentTimeMillis()));
  }

  private void updateStatus(@NonNull String statusLine) {
    lastStatusLine = statusLine;
    statusListener.onStatusChanged(statusLine);
  }

  void onNetworkAvailable() {
    handleNetworkAvailable();
  }

  private int resolveBatchSize(long nowMs) {
    int queued = queueStore.countQueuedEvents(nowMs);
    if (queued <= UPLOAD_BATCH_SIZE) {
      return UPLOAD_BATCH_SIZE;
    }
    return Math.min(MAX_UPLOAD_BATCH_SIZE, Math.max(UPLOAD_BATCH_SIZE, queued / 2));
  }

  @NonNull
  private UploadFailureClass toFailureClass(@NonNull UploadReceipt receipt) {
    switch (receipt.getFailureCategory()) {
      case NETWORK:
        return UploadFailureClass.NETWORK;
      case TIMEOUT:
        return UploadFailureClass.TIMEOUT;
      case SERVER:
        return UploadFailureClass.SERVER;
      case CLIENT:
        return UploadFailureClass.CLIENT;
      case RESPONSE_PARSE:
        return UploadFailureClass.RESPONSE_PARSE;
      case UNKNOWN:
        return UploadFailureClass.UNKNOWN;
      case NONE:
      default:
        return UploadFailureClass.NONE;
    }
  }

  private boolean isNetworkAvailable() {
    return networkChecker.getAsBoolean();
  }

  private void handleNetworkAvailable() {
    if (!closed) {
      updateStatus("事件上报：网络恢复，开始重试");
      triggerUpload();
    }
  }

  private boolean isNetworkAvailableDefault() {
    ConnectivityManager manager = connectivityManager;
    if (manager == null) {
      return true;
    }
    try {
      Network activeNetwork = manager.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
      if (capabilities == null) {
        return false;
      }
      boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      boolean validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      return hasInternet && (validated || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
    } catch (Exception ignored) {
      return true;
    }
  }

  @NonNull
  private RiskEventCandidate toFatigueCandidate(@NonNull LocalFatigueAnalyzer.Result fatigue, long eventTimeMs) {
    Set<TriggerReason> triggerReasons = new LinkedHashSet<>();
    if (fatigue.eyesClosed) {
      triggerReasons.add(TriggerReason.FATIGUE_PERCLOS_SUSTAINED);
    }
    if (fatigue.yawning) {
      triggerReasons.add(TriggerReason.FATIGUE_YAWN_FREQUENT);
    }
    if (triggerReasons.isEmpty()) {
      triggerReasons.add(TriggerReason.FATIGUE_PERCLOS_SUSTAINED);
    }

    double fatigueScore = Math.max(0.0, Math.min(1.0, fatigue.fatigueScore));
    return new RiskEventCandidate(
      Math.max(0L, eventTimeMs - 3_000L),
      eventTimeMs,
      fatigueScore,
      0.0,
      toRiskLevel(fatigueScore),
      RiskType.FATIGUE,
      true,
      false,
      true,
      triggerReasons
    );
  }

  @NonNull
  private RiskLevel toRiskLevel(double fatigueScore) {
    if (fatigueScore >= 0.85) {
      return RiskLevel.HIGH;
    }
    if (fatigueScore >= 0.7) {
      return RiskLevel.MEDIUM;
    }
    return RiskLevel.LOW;
  }

  @Nullable
  private String firstNonBlank(@Nullable String first, @Nullable String second, @Nullable String fallback) {
    if (!isBlank(first)) {
      return first;
    }
    if (!isBlank(second)) {
      return second;
    }
    return fallback;
  }

  private boolean isBlank(@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }

  public interface StatusListener {
    void onStatusChanged(@NonNull String statusLine);
  }

  interface ReporterQueueStore extends EdgeEventDao, DeviceConfigDao {
    int countQueuedEvents(long nowMs);

    boolean hasReadyEvents(long nowMs);

    @Nullable
    Long nextRetryAtMs(long nowMs);
  }

  static class PrefsQueueStore implements ReporterQueueStore {
    private static final String PREFS_NAME = "driveedge_event_queue";
    private static final String KEY_EDGE_EVENTS = "edge_events";
    private static final String KEY_DEVICE_CONFIGS = "device_configs";

    @Nullable
    private final SharedPreferences prefs;
    @NonNull
    private final LinkedHashMap<String, EdgeEventRow> edgeEvents = new LinkedHashMap<>();
    @NonNull
    private final LinkedHashMap<String, DeviceConfigRow> deviceConfigs = new LinkedHashMap<>();

    PrefsQueueStore(@NonNull Context context) {
      prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      load();
    }

    PrefsQueueStore() {
      prefs = null;
    }

    @Override
    public synchronized void upsert(@NonNull EdgeEventRow row) {
      EdgeEventRow existing = edgeEvents.get(row.getEventId());
      if (existing == null) {
        edgeEvents.put(row.getEventId(), row);
      } else {
        edgeEvents.put(
          row.getEventId(),
          new EdgeEventRow(
            row.getEvent(),
            existing.getUploadStatus(),
            existing.getRetryCount(),
            existing.getLastErrorCode(),
            existing.getLastErrorMessage(),
            existing.getServerTraceId(),
            existing.getNextRetryAtMs(),
            existing.getLastAttemptAtMs(),
            existing.getFailureClass(),
            row.getUpdatedAtMs()
          )
        );
      }
      persistEdgeEvents();
    }

    @Override
    public synchronized EdgeEventRow getByEventId(String eventId) {
      return edgeEvents.get(eventId);
    }

    @Override
    public synchronized void update(@NonNull EdgeEventRow row) {
      edgeEvents.put(row.getEventId(), row);
      persistEdgeEvents();
    }

    @Override
    public synchronized List<EdgeEventRow> listReadyForUpload(long nowMs, int limit) {
      if (limit <= 0) {
        return new ArrayList<>();
      }
      List<EdgeEventRow> readyRows = new ArrayList<>();
      for (EdgeEventRow row : edgeEvents.values()) {
        if (isReadyForUpload(row, nowMs)) {
          readyRows.add(row);
        }
      }
      readyRows.sort((left, right) -> {
        int priorityCompare = Integer.compare(priorityOf(left), priorityOf(right));
        if (priorityCompare != 0) {
          return priorityCompare;
        }
        long leftRetryAt = left.getNextRetryAtMs() == null ? Long.MIN_VALUE : left.getNextRetryAtMs();
        long rightRetryAt = right.getNextRetryAtMs() == null ? Long.MIN_VALUE : right.getNextRetryAtMs();
        if (leftRetryAt != rightRetryAt) {
          return Long.compare(leftRetryAt, rightRetryAt);
        }
        long leftAttemptAt = left.getLastAttemptAtMs() == null ? Long.MIN_VALUE : left.getLastAttemptAtMs();
        long rightAttemptAt = right.getLastAttemptAtMs() == null ? Long.MIN_VALUE : right.getLastAttemptAtMs();
        if (leftAttemptAt != rightAttemptAt) {
          return Long.compare(leftAttemptAt, rightAttemptAt);
        }
        return Long.compare(left.getEvent().getCreatedAtMs(), right.getEvent().getCreatedAtMs());
      });
      return readyRows.size() > limit ? new ArrayList<>(readyRows.subList(0, limit)) : readyRows;
    }

    @Override
    public synchronized void upsert(@NonNull DeviceConfigRow row) {
      deviceConfigs.put(row.getDeviceId(), row);
      persistDeviceConfigs();
    }

    @Override
    public synchronized DeviceConfigRow getByDeviceId(String deviceId) {
      return deviceConfigs.get(deviceId);
    }

    @Override
    public synchronized int countQueuedEvents(long nowMs) {
      int count = 0;
      for (EdgeEventRow row : edgeEvents.values()) {
        if (row.getUploadStatus() == UploadStatus.PENDING || row.getUploadStatus() == UploadStatus.RETRY_WAIT || row.getUploadStatus() == UploadStatus.SENDING) {
          count++;
        }
      }
      return count;
    }

    @Override
    public synchronized boolean hasReadyEvents(long nowMs) {
      for (EdgeEventRow row : edgeEvents.values()) {
        if (isReadyForUpload(row, nowMs)) {
          return true;
        }
      }
      return false;
    }

    @Nullable
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
      if (nextRetryAtMs != null && nextRetryAtMs <= nowMs) {
        return nowMs;
      }
      return nextRetryAtMs;
    }

    private boolean isReadyForUpload(@NonNull EdgeEventRow row, long nowMs) {
      switch (row.getUploadStatus()) {
        case PENDING:
          return true;
        case RETRY_WAIT:
          return row.getNextRetryAtMs() != null && row.getNextRetryAtMs() <= nowMs;
        case SENDING:
        case SUCCESS:
        case FAILED_FINAL:
        default:
          return false;
      }
    }

    private void load() {
      if (prefs == null) {
        return;
      }
      edgeEvents.clear();
      deviceConfigs.clear();
      loadEdgeEvents();
      loadDeviceConfigs();
    }

    private void loadEdgeEvents() {
      if (prefs == null) {
        return;
      }
      String encoded = prefs.getString(KEY_EDGE_EVENTS, "[]");
      if (encoded == null) {
        return;
      }
      try {
        JSONArray array = new JSONArray(encoded);
        for (int index = 0; index < array.length(); index++) {
          JSONObject object = array.optJSONObject(index);
          if (object == null) {
            continue;
          }
          EdgeEventRow row = decodeEdgeEventRow(object);
          edgeEvents.put(row.getEventId(), row);
        }
      } catch (JSONException ignored) {
      }
    }

    private void loadDeviceConfigs() {
      if (prefs == null) {
        return;
      }
      String encoded = prefs.getString(KEY_DEVICE_CONFIGS, "[]");
      if (encoded == null) {
        return;
      }
      try {
        JSONArray array = new JSONArray(encoded);
        for (int index = 0; index < array.length(); index++) {
          JSONObject object = array.optJSONObject(index);
          if (object == null) {
            continue;
          }
          DeviceConfigRow row = decodeDeviceConfigRow(object);
          deviceConfigs.put(row.getDeviceId(), row);
        }
      } catch (JSONException ignored) {
      }
    }

    private void persistEdgeEvents() {
      if (prefs == null) {
        return;
      }
      JSONArray array = new JSONArray();
      for (EdgeEventRow row : edgeEvents.values()) {
        array.put(encodeEdgeEventRow(row));
      }
      prefs.edit().putString(KEY_EDGE_EVENTS, array.toString()).apply();
    }

    private void persistDeviceConfigs() {
      if (prefs == null) {
        return;
      }
      JSONArray array = new JSONArray();
      for (DeviceConfigRow row : deviceConfigs.values()) {
        array.put(encodeDeviceConfigRow(row));
      }
      prefs.edit().putString(KEY_DEVICE_CONFIGS, array.toString()).apply();
    }

    @NonNull
    private JSONObject encodeEdgeEventRow(@NonNull EdgeEventRow row) {
      JSONObject object = new JSONObject();
      try {
        EdgeEvent event = row.getEvent();
        object.put("eventId", event.getEventId());
        object.put("deviceCode", event.getDeviceCode());
        object.put("reportedEnterpriseId", event.getReportedEnterpriseId() == null ? JSONObject.NULL : event.getReportedEnterpriseId());
        object.put("fleetId", event.getFleetId());
        object.put("vehicleId", event.getVehicleId());
        object.put("driverId", event.getDriverId());
        object.put("sessionId", event.getSessionId() == null ? JSONObject.NULL : event.getSessionId());
        object.put("configVersion", event.getConfigVersion() == null ? JSONObject.NULL : event.getConfigVersion());
        object.put("eventTimeUtc", event.getEventTimeUtc());
        object.put("fatigueScore", event.getFatigueScore());
        object.put("distractionScore", event.getDistractionScore());
        object.put("riskLevel", event.getRiskLevel().name());
        object.put("dominantRiskType", event.getDominantRiskType() == null ? JSONObject.NULL : event.getDominantRiskType().name());
        JSONArray triggerReasons = new JSONArray();
        for (TriggerReason reason : event.getTriggerReasons()) {
          triggerReasons.put(reason.name());
        }
        object.put("triggerReasons", triggerReasons);
        object.put("algorithmVer", event.getAlgorithmVer());
        object.put("uploadStatus", normalizePersistedStatus(row.getUploadStatus()).name());
        object.put("windowStartMs", event.getWindowStartMs());
        object.put("windowEndMs", event.getWindowEndMs());
        object.put("createdAtMs", event.getCreatedAtMs());
        object.put("retryCount", row.getRetryCount());
        object.put("lastErrorCode", row.getLastErrorCode() == null ? JSONObject.NULL : row.getLastErrorCode());
        object.put("lastErrorMessage", row.getLastErrorMessage() == null ? JSONObject.NULL : row.getLastErrorMessage());
        object.put("serverTraceId", row.getServerTraceId() == null ? JSONObject.NULL : row.getServerTraceId());
        object.put("nextRetryAtMs", row.getNextRetryAtMs() == null ? JSONObject.NULL : row.getNextRetryAtMs());
        object.put("lastAttemptAtMs", row.getLastAttemptAtMs() == null ? JSONObject.NULL : row.getLastAttemptAtMs());
        object.put("failureClass", row.getFailureClass().name());
        object.put("updatedAtMs", row.getUpdatedAtMs());
      } catch (JSONException ignored) {
      }
      return object;
    }

    @NonNull
    private EdgeEventRow decodeEdgeEventRow(@NonNull JSONObject object) {
      JSONArray triggerReasonArray = object.optJSONArray("triggerReasons");
      Set<TriggerReason> triggerReasons = new LinkedHashSet<>();
      if (triggerReasonArray != null) {
        for (int index = 0; index < triggerReasonArray.length(); index++) {
          String reasonName = triggerReasonArray.optString(index, null);
          if (reasonName == null || reasonName.trim().isEmpty()) {
            continue;
          }
          try {
            triggerReasons.add(TriggerReason.valueOf(reasonName));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
      RiskType dominantRiskType = null;
      String dominantRiskTypeName = object.optString("dominantRiskType", "");
      if (!dominantRiskTypeName.trim().isEmpty() && !"null".equalsIgnoreCase(dominantRiskTypeName)) {
        try {
          dominantRiskType = RiskType.valueOf(dominantRiskTypeName);
        } catch (IllegalArgumentException ignored) {
        }
      }
      UploadStatus uploadStatus = UploadStatus.valueOf(object.optString("uploadStatus", UploadStatus.PENDING.name()));
      if (uploadStatus == UploadStatus.SENDING) {
        uploadStatus = UploadStatus.RETRY_WAIT;
      }
      UploadFailureClass failureClass = UploadFailureClass.NONE;
      String failureClassName = object.optString("failureClass", UploadFailureClass.NONE.name());
      try {
        failureClass = UploadFailureClass.valueOf(failureClassName);
      } catch (IllegalArgumentException ignored) {
      }
      EdgeEvent event = new EdgeEvent(
        object.optString("eventId", ""),
        object.optString("deviceCode", BuildConfig.EDGE_DEVICE_CODE),
        object.isNull("reportedEnterpriseId") ? null : object.optString("reportedEnterpriseId", null),
        object.isNull("fleetId") ? null : object.optString("fleetId", null),
        object.optString("vehicleId", ""),
        object.isNull("driverId") ? null : object.optString("driverId", null),
        object.isNull("sessionId") ? null : object.optLong("sessionId"),
        object.isNull("configVersion") ? null : object.optString("configVersion", null),
        object.optString("eventTimeUtc", ""),
        object.optDouble("fatigueScore", 0.0),
        object.optDouble("distractionScore", 0.0),
        RiskLevel.valueOf(object.optString("riskLevel", RiskLevel.LOW.name())),
        dominantRiskType,
        triggerReasons,
        object.optString("algorithmVer", "unknown"),
        uploadStatus,
        object.optLong("windowStartMs", 0L),
        object.optLong("windowEndMs", 0L),
        object.optLong("createdAtMs", 0L)
      );
      Long nextRetryAtMs = object.isNull("nextRetryAtMs") ? null : object.optLong("nextRetryAtMs");
      return new EdgeEventRow(
        event,
        uploadStatus,
        object.optInt("retryCount", 0),
        object.isNull("lastErrorCode") ? null : object.optInt("lastErrorCode"),
        object.isNull("lastErrorMessage") ? null : object.optString("lastErrorMessage", null),
        object.isNull("serverTraceId") ? null : object.optString("serverTraceId", null),
        nextRetryAtMs,
        object.isNull("lastAttemptAtMs") ? null : object.optLong("lastAttemptAtMs"),
        failureClass,
        object.optLong("updatedAtMs", event.getCreatedAtMs())
      );
    }

    @NonNull
    private JSONObject encodeDeviceConfigRow(@NonNull DeviceConfigRow row) {
      JSONObject object = new JSONObject();
      try {
        object.put("fleetId", row.getFleetId());
        object.put("vehicleId", row.getVehicleId());
        object.put("deviceId", row.getDeviceId());
        object.put("modelProfile", row.getModelProfile());
        object.put("thresholdProfile", row.getThresholdProfile());
        object.put("uploadPolicy", row.getUploadPolicy());
        object.put("updatedAtMs", row.getUpdatedAtMs());
      } catch (JSONException ignored) {
      }
      return object;
    }

    @NonNull
    private DeviceConfigRow decodeDeviceConfigRow(@NonNull JSONObject object) {
      return new DeviceConfigRow(
        object.isNull("fleetId") ? null : object.optString("fleetId", null),
        object.optString("vehicleId", ""),
        object.optString("deviceId", ""),
        object.optString("modelProfile", ""),
        object.optString("thresholdProfile", ""),
        object.optString("uploadPolicy", ""),
        object.optLong("updatedAtMs", 0L)
      );
    }

    @NonNull
    private UploadStatus normalizePersistedStatus(@NonNull UploadStatus uploadStatus) {
      return uploadStatus == UploadStatus.SENDING ? UploadStatus.RETRY_WAIT : uploadStatus;
    }

    private int priorityOf(@NonNull EdgeEventRow row) {
      switch (row.getUploadStatus()) {
        case RETRY_WAIT:
          return 0;
        case PENDING:
          return 1;
        default:
          return 2;
      }
    }
  }

  @NonNull
  static ReporterQueueStore createQueueStore(@NonNull Context context) {
    try {
      return new SQLiteOutboxStore(context.getApplicationContext());
    } catch (Exception ignored) {
      try {
        return new PrefsQueueStore(context.getApplicationContext());
      } catch (Exception nested) {
        return new PrefsQueueStore();
      }
    }
  }

  @NonNull
  static StorageCenter createStorageCenter(@NonNull ReporterQueueStore store) {
    return new StorageCenter(store, store, new StorageConfig(), Clock.systemUTC());
  }

  @Nullable
  static EventUploader createEventUploader() {
    return createEventUploader(null, null);
  }

  @Nullable
  static EventUploader createEventUploader(@Nullable Context context, @Nullable EdgeContextStore contextStore) {
    EdgeLocalContext localContext = contextStore == null ? null : contextStore.load();
    String deviceCode = firstNonBlankStatic(
      localContext == null ? null : localContext.deviceCode,
      BuildConfig.EDGE_DEVICE_CODE,
      null
    );
    String deviceToken = firstNonBlankStatic(
      localContext == null ? null : localContext.deviceToken,
      BuildConfig.EDGE_DEVICE_TOKEN,
      null
    );
    if (isBlankStatic(deviceCode) || isBlankStatic(deviceToken)) {
      return null;
    }
    return new EventUploader(
      new UploaderConfig(
        BuildConfig.EDGE_SERVER_BASE_URL,
        deviceCode,
        deviceToken,
        "/api/v1/events",
        "Idempotency-Key",
        "X-Event-Id",
        Duration.ofSeconds(5),
        Duration.ofSeconds(8)
      )
    );
  }

  @Nullable
  private EventCenter createEventCenter() {
    EdgeContextStore store = edgeContextStore;
    if (store == null) {
      return null;
    }
    EdgeLocalContext context = store.load();
    if (!context.hasVehicleBinding() || isBlank(context.deviceCode)) {
      return null;
    }
    EdgeEventStore eventStore = event -> storageCenter.onEdgeEvent(event, System.currentTimeMillis());
    return new EventCenter(
      new EventCenterConfig(
        context.deviceCode,
        String.valueOf(context.vehicleId),
        context.enterpriseId == null ? null : String.valueOf(context.enterpriseId),
        context.fleetId == null ? null : String.valueOf(context.fleetId),
        context.driverId == null ? null : String.valueOf(context.driverId),
        context.sessionId,
        context.configVersion,
        BuildConfig.EDGE_ALGORITHM_VERSION,
        8_000L
      ),
      eventStore,
      new EventIdGenerator(String.valueOf(context.vehicleId)),
      eventDebouncer
    );
  }

  @Nullable
  private static String firstNonBlankStatic(@Nullable String first, @Nullable String second, @Nullable String fallback) {
    if (!isBlankStatic(first)) {
      return first;
    }
    if (!isBlankStatic(second)) {
      return second;
    }
    return fallback;
  }

  private static boolean isBlankStatic(@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }
}
