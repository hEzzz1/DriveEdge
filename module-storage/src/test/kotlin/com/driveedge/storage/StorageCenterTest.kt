package com.driveedge.storage

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.UploadStatus
import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageCenterTest {
  @Test
  fun `onEdgeEvent initializes record as pending and exposes it in queue`() {
    val center = StorageCenter()

    center.onEdgeEvent(event(eventId = "evt-1", createdAtMs = 1_000L), nowMs = 1_000L)

    val queue = center.pendingUploadQueue(nowMs = 1_000L)
    assertEquals(1, queue.size)
    assertEquals("evt-1", queue.first().event.eventId)
    assertEquals(UploadStatus.PENDING, queue.first().event.uploadStatus)
  }

  @Test
  fun `claimUploadBatch marks queued event as sending`() {
    val center = StorageCenter()
    center.onEdgeEvent(event(eventId = "evt-1", createdAtMs = 1_000L), nowMs = 1_000L)

    val claimed = center.claimUploadBatch(limit = 10, nowMs = 1_500L)

    assertEquals(1, claimed.size)
    assertEquals(UploadStatus.SENDING, claimed.first().event.uploadStatus)
    assertTrue(center.pendingUploadQueue(nowMs = 1_500L).isEmpty())
  }

  @Test
  fun `onUploadResult moves sending event to success`() {
    val center = StorageCenter()
    center.onEdgeEvent(event(eventId = "evt-success", createdAtMs = 1_000L), nowMs = 1_000L)
    center.claimUploadBatch(limit = 1, nowMs = 1_100L)

    center.onUploadResult(
      UploadAttemptResult(
        eventId = "evt-success",
        code = 0,
        serverTraceId = "trace-1",
      ),
      nowMs = 1_200L,
    )

    val row = center.getEventRow("evt-success")
    assertNotNull(row)
    assertEquals(UploadStatus.SUCCESS, row.uploadStatus)
    assertEquals("trace-1", row.serverTraceId)
    assertTrue(center.pendingUploadQueue(nowMs = 1_200L).isEmpty())
  }

  @Test
  fun `onUploadResult moves event to retry wait and re-enters queue after backoff`() {
    val center = StorageCenter()
    center.onEdgeEvent(event(eventId = "evt-retry", createdAtMs = 1_000L), nowMs = 1_000L)
    center.claimUploadBatch(limit = 1, nowMs = 1_100L)

    center.onUploadResult(
      UploadAttemptResult(
        eventId = "evt-retry",
        code = 50001,
        errorMessage = "server busy",
      ),
      nowMs = 2_000L,
    )

    val row = center.getEventRow("evt-retry")
    assertNotNull(row)
    assertEquals(UploadStatus.RETRY_WAIT, row.uploadStatus)
    assertEquals(1, row.retryCount)
    assertTrue(row.nextRetryAtMs!! >= 7_000L)
    assertEquals(UploadFailureClass.NONE, row.failureClass)
    assertEquals(2_000L, row.lastAttemptAtMs)

    assertTrue(center.pendingUploadQueue(nowMs = row.nextRetryAtMs!! - 1L).isEmpty())
    assertEquals(1, center.pendingUploadQueue(nowMs = row.nextRetryAtMs!!).size)
  }

  @Test
  fun `onUploadResult moves event to failed final on non-retryable code`() {
    val center = StorageCenter()
    center.onEdgeEvent(event(eventId = "evt-final", createdAtMs = 1_000L), nowMs = 1_000L)
    center.claimUploadBatch(limit = 1, nowMs = 1_100L)

    center.onUploadResult(
      UploadAttemptResult(
        eventId = "evt-final",
        code = 40001,
        errorMessage = "invalid token",
      ),
      nowMs = 2_000L,
    )

    val row = center.getEventRow("evt-final")
    assertNotNull(row)
    assertEquals(UploadStatus.FAILED_FINAL, row.uploadStatus)
    assertEquals(40001, row.lastErrorCode)
    assertEquals("invalid token", row.lastErrorMessage)
    assertTrue(center.pendingUploadQueue(nowMs = 2_000L).isEmpty())
  }

  @Test
  fun `onUploadResult moves event to failed final when max retry exceeded`() {
    val center = StorageCenter(config = StorageConfig(maxRetryCount = 1))
    center.onEdgeEvent(event(eventId = "evt-max-retry", createdAtMs = 1_000L), nowMs = 1_000L)
    center.claimUploadBatch(limit = 1, nowMs = 1_100L)

    center.onUploadResult(
      UploadAttemptResult(eventId = "evt-max-retry", code = 50002),
      nowMs = 2_000L,
    )
    center.claimUploadBatch(limit = 1, nowMs = 7_000L)
    center.onUploadResult(
      UploadAttemptResult(eventId = "evt-max-retry", code = 50002),
      nowMs = 8_000L,
    )

    val row = center.getEventRow("evt-max-retry")
    assertNotNull(row)
    assertEquals(UploadStatus.FAILED_FINAL, row.uploadStatus)
    assertEquals(2, row.retryCount)
    assertNull(row.nextRetryAtMs)
  }

  @Test
  fun `onUploadResult persists failure class and deterministic jitter`() {
    val center =
      StorageCenter(
        config =
          StorageConfig(
            retryBackoffPolicy = RetryBackoffPolicy(
              scheduleMs = listOf(5_000L),
              maxBackoffMs = 5_000L,
              jitterUpperBoundMs = 500L,
            ),
          ),
      )
    center.onEdgeEvent(event(eventId = "evt-jitter", createdAtMs = 1_000L), nowMs = 1_000L)
    center.claimUploadBatch(limit = 1, nowMs = 1_100L)

    center.onUploadResult(
      UploadAttemptResult(
        eventId = "evt-jitter",
        code = -1,
        errorMessage = "timeout",
        failureClass = UploadFailureClass.TIMEOUT,
      ),
      nowMs = 2_000L,
    )

    val row = center.getEventRow("evt-jitter")
    assertNotNull(row)
    assertEquals(UploadFailureClass.TIMEOUT, row.failureClass)
    assertTrue(row.nextRetryAtMs!! in 7_000L..7_500L)
  }

  private fun event(
    eventId: String,
    createdAtMs: Long,
  ): EdgeEvent =
    EdgeEvent(
      eventId = eventId,
      fleetId = "fleet-1",
      vehicleId = "veh-1",
      driverId = "driver-1",
      eventTimeUtc = "2026-04-09T10:00:00Z",
      fatigueScore = 0.81,
      distractionScore = 0.22,
      riskLevel = RiskLevel.HIGH,
      dominantRiskType = RiskType.FATIGUE,
      triggerReasons = setOf(TriggerReason.FATIGUE_PERCLOS_SUSTAINED),
      algorithmVer = "yolo-v8n-int8-20260407",
      uploadStatus = UploadStatus.PENDING,
      windowStartMs = 9_000L,
      windowEndMs = 12_000L,
      createdAtMs = createdAtMs,
    )
}
