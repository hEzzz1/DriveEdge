package com.driveedge.storage

import com.driveedge.event.center.UploadStatus

class InMemoryRoomStore : EdgeEventDao, DeviceConfigDao {
  private val edgeEvents = linkedMapOf<String, EdgeEventRow>()
  private val deviceConfigs = linkedMapOf<String, DeviceConfigRow>()

  @Synchronized
  override fun upsert(row: EdgeEventRow) {
    val existing = edgeEvents[row.eventId]
    edgeEvents[row.eventId] =
      if (existing == null) {
        row
      } else {
        existing.copy(
          event = row.event,
          updatedAtMs = row.updatedAtMs,
        )
      }
  }

  @Synchronized
  override fun getByEventId(eventId: String): EdgeEventRow? = edgeEvents[eventId]

  @Synchronized
  override fun update(row: EdgeEventRow) {
    edgeEvents[row.eventId] = row
  }

  @Synchronized
  override fun listReadyForUpload(
    nowMs: Long,
    limit: Int,
  ): List<EdgeEventRow> {
    if (limit <= 0) {
      return emptyList()
    }
    return edgeEvents.values
      .asSequence()
      .filter { isReadyForUpload(it, nowMs) }
      .sortedBy { it.event.createdAtMs }
      .take(limit)
      .toList()
  }

  @Synchronized
  override fun upsert(row: DeviceConfigRow) {
    deviceConfigs[row.deviceId] = row
  }

  @Synchronized
  override fun getByDeviceId(deviceId: String): DeviceConfigRow? = deviceConfigs[deviceId]

  private fun isReadyForUpload(
    row: EdgeEventRow,
    nowMs: Long,
  ): Boolean =
    when (row.uploadStatus) {
      UploadStatus.PENDING -> true
      UploadStatus.RETRY_WAIT -> (row.nextRetryAtMs ?: Long.MAX_VALUE) <= nowMs
      UploadStatus.SENDING,
      UploadStatus.SUCCESS,
      UploadStatus.FAILED_FINAL,
      -> false
    }
}
