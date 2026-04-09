package com.driveedge.storage

interface EdgeEventDao {
  fun upsert(row: EdgeEventRow)

  fun getByEventId(eventId: String): EdgeEventRow?

  fun update(row: EdgeEventRow)

  fun listReadyForUpload(
    nowMs: Long,
    limit: Int,
  ): List<EdgeEventRow>
}

interface DeviceConfigDao {
  fun upsert(row: DeviceConfigRow)

  fun getByDeviceId(deviceId: String): DeviceConfigRow?
}
