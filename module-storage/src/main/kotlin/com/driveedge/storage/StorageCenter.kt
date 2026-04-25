package com.driveedge.storage

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.UploadStatus
import java.time.Clock

class StorageCenter(
  private val edgeEventDao: EdgeEventDao,
  private val deviceConfigDao: DeviceConfigDao,
  private val config: StorageConfig = StorageConfig(),
  private val clock: Clock = Clock.systemUTC(),
) {
  constructor(
    store: InMemoryRoomStore = InMemoryRoomStore(),
    config: StorageConfig = StorageConfig(),
    clock: Clock = Clock.systemUTC(),
  ) : this(store, store, config, clock)

  fun onEdgeEvent(
    event: EdgeEvent,
    nowMs: Long = clock.millis(),
  ): EdgeEventRow {
    val row =
      EdgeEventRow(
        event = event.copy(uploadStatus = UploadStatus.PENDING),
        uploadStatus = UploadStatus.PENDING,
        failureClass = UploadFailureClass.NONE,
        updatedAtMs = nowMs,
      )
    edgeEventDao.upsert(row)
    return row
  }

  fun upsertDeviceConfig(row: DeviceConfigRow) {
    deviceConfigDao.upsert(row)
  }

  fun getDeviceConfig(deviceId: String): DeviceConfigRow? = deviceConfigDao.getByDeviceId(deviceId)

  fun pendingUploadQueue(
    limit: Int = config.defaultBatchSize,
    nowMs: Long = clock.millis(),
  ): List<UploadQueueItem> =
    edgeEventDao.listReadyForUpload(nowMs, limit).map { it.toQueueItem() }

  fun claimUploadBatch(
    limit: Int = config.defaultBatchSize,
    nowMs: Long = clock.millis(),
  ): List<UploadQueueItem> {
    val readyRows = edgeEventDao.listReadyForUpload(nowMs, limit)
    if (readyRows.isEmpty()) {
      return emptyList()
    }
    return readyRows.map { row ->
      val claimed =
        row.copy(
          uploadStatus = UploadStatus.SENDING,
          nextRetryAtMs = null,
          lastAttemptAtMs = nowMs,
          updatedAtMs = nowMs,
        )
      edgeEventDao.update(claimed)
      claimed.toQueueItem()
    }
  }

  fun onUploadResult(
    result: UploadAttemptResult,
    nowMs: Long = clock.millis(),
  ): EdgeEventRow? {
    val current = edgeEventDao.getByEventId(result.eventId) ?: return null
    if (current.uploadStatus == UploadStatus.SUCCESS || current.uploadStatus == UploadStatus.FAILED_FINAL) {
      return current
    }

    val updated =
      when {
        result.code in SUCCESS_CODES -> {
          current.copy(
            uploadStatus = UploadStatus.SUCCESS,
            lastErrorCode = null,
            lastErrorMessage = null,
            serverTraceId = result.serverTraceId ?: current.serverTraceId,
            nextRetryAtMs = null,
            lastAttemptAtMs = nowMs,
            failureClass = UploadFailureClass.NONE,
            updatedAtMs = nowMs,
          )
        }

        result.code in FINAL_FAILURE_CODES -> {
          current.copy(
            uploadStatus = UploadStatus.FAILED_FINAL,
            lastErrorCode = result.code,
            lastErrorMessage = result.errorMessage,
            serverTraceId = result.serverTraceId ?: current.serverTraceId,
            nextRetryAtMs = null,
            lastAttemptAtMs = nowMs,
            failureClass = result.failureClass,
            updatedAtMs = nowMs,
          )
        }

        else -> toRetryOrFinal(current = current, result = result, nowMs = nowMs)
      }

    edgeEventDao.update(updated)
    return updated
  }

  fun getEventRow(eventId: String): EdgeEventRow? = edgeEventDao.getByEventId(eventId)

  private fun toRetryOrFinal(
    current: EdgeEventRow,
    result: UploadAttemptResult,
    nowMs: Long,
  ): EdgeEventRow {
    val nextRetryCount = current.retryCount + 1
    if (nextRetryCount > config.maxRetryCount) {
      return current.copy(
        uploadStatus = UploadStatus.FAILED_FINAL,
        retryCount = nextRetryCount,
        lastErrorCode = result.code,
        lastErrorMessage = result.errorMessage ?: "Retry limit exceeded",
        serverTraceId = result.serverTraceId ?: current.serverTraceId,
        nextRetryAtMs = null,
        lastAttemptAtMs = nowMs,
        failureClass = result.failureClass,
        updatedAtMs = nowMs,
      )
    }
    val delayMs = config.retryBackoffPolicy.delayMsForAttempt(nextRetryCount, current.eventId)
    return current.copy(
      uploadStatus = UploadStatus.RETRY_WAIT,
      retryCount = nextRetryCount,
      lastErrorCode = result.code,
      lastErrorMessage = result.errorMessage,
      serverTraceId = result.serverTraceId ?: current.serverTraceId,
      nextRetryAtMs = nowMs + delayMs,
      lastAttemptAtMs = nowMs,
      failureClass = result.failureClass,
      updatedAtMs = nowMs,
    )
  }

  private fun EdgeEventRow.toQueueItem(): UploadQueueItem =
    UploadQueueItem(
      event = asEdgeEvent(),
      retryCount = retryCount,
      lastErrorCode = lastErrorCode,
      lastErrorMessage = lastErrorMessage,
      nextRetryAtMs = nextRetryAtMs,
      lastAttemptAtMs = lastAttemptAtMs,
      failureClass = failureClass,
    )

  private companion object {
    val SUCCESS_CODES: Set<Int> = setOf(0, 40002)
    val FINAL_FAILURE_CODES: Set<Int> = setOf(40001, 40101)
  }
}
