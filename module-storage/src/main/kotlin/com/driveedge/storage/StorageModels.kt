package com.driveedge.storage

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.UploadStatus

data class EdgeEventRow(
  val event: EdgeEvent,
  val uploadStatus: UploadStatus = event.uploadStatus,
  val retryCount: Int = 0,
  val lastErrorCode: Int? = null,
  val lastErrorMessage: String? = null,
  val serverTraceId: String? = null,
  val nextRetryAtMs: Long? = null,
  val updatedAtMs: Long = event.createdAtMs,
) {
  val eventId: String
    get() = event.eventId

  fun asEdgeEvent(): EdgeEvent = event.copy(uploadStatus = uploadStatus)
}

data class DeviceConfigRow(
  val fleetId: String?,
  val vehicleId: String,
  val deviceId: String,
  val modelProfile: String,
  val thresholdProfile: String,
  val uploadPolicy: String,
  val updatedAtMs: Long,
)

data class UploadAttemptResult(
  val eventId: String,
  val code: Int,
  val errorMessage: String? = null,
  val serverTraceId: String? = null,
)

data class UploadQueueItem(
  val event: EdgeEvent,
  val retryCount: Int,
  val lastErrorCode: Int?,
  val lastErrorMessage: String?,
  val nextRetryAtMs: Long?,
)
