package com.driveedge.event.center

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EventIdGenerator(
  vehicleId: String,
) {
  private val safeVehicleId = sanitize(vehicleId)

  @Volatile
  private var lastSecond: Long = Long.MIN_VALUE
  private var sequence: Int = 0

  @Synchronized
  fun next(eventTimeMs: Long): String {
    val instant = Instant.ofEpochMilli(eventTimeMs)
    val second = instant.epochSecond
    if (second != lastSecond) {
      lastSecond = second
      sequence = 0
    }
    sequence += 1

    val timestamp = EVENT_ID_TIMESTAMP_FORMATTER.format(instant)
    return "evt_${safeVehicleId}_${timestamp}_${sequence.toString().padStart(4, '0')}"
  }

  private companion object {
    val EVENT_ID_TIMESTAMP_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    private fun sanitize(raw: String): String =
      raw.trim().replace("[^A-Za-z0-9_-]".toRegex(), "_")
  }
}
