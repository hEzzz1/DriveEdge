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
  override fun claimReadyForUpload(
    nowMs: Long,
    limit: Int,
    leaseUntilMs: Long,
  ): List<EdgeEventRow> {
    if (limit <= 0) {
      return emptyList()
    }
    val readyRows = readyRows(nowMs).take(limit)
    return readyRows.map { row ->
      val claimed =
        row.copy(
          uploadStatus = UploadStatus.SENDING,
          nextRetryAtMs = leaseUntilMs,
          lastAttemptAtMs = nowMs,
          updatedAtMs = nowMs,
        )
      edgeEvents[row.eventId] = claimed
      claimed
    }
  }

  @Synchronized
  override fun listReadyForUpload(
    nowMs: Long,
    limit: Int,
  ): List<EdgeEventRow> {
    if (limit <= 0) {
      return emptyList()
    }
    return readyRows(nowMs).take(limit)
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
      UploadStatus.RETRY_WAIT,
      UploadStatus.SENDING,
      -> (row.nextRetryAtMs ?: Long.MIN_VALUE) <= nowMs
      UploadStatus.SUCCESS,
      UploadStatus.FAILED_FINAL,
      -> false
    }

  private fun readyRows(nowMs: Long): List<EdgeEventRow> =
    edgeEvents.values
      .asSequence()
      .filter { isReadyForUpload(it, nowMs) }
      .sortedWith(
        compareBy<EdgeEventRow>(
          { priorityOf(it) },
          { it.nextRetryAtMs ?: Long.MIN_VALUE },
          { it.lastAttemptAtMs ?: Long.MIN_VALUE },
          { it.event.createdAtMs },
        ),
      )
      .toList()

  private fun priorityOf(row: EdgeEventRow): Int =
    when (row.uploadStatus) {
      UploadStatus.RETRY_WAIT -> 0
      UploadStatus.SENDING -> 1
      UploadStatus.PENDING -> 2
      else -> 3
    }
}
