package com.driveedge.alert

class AlertThrottler {
  private val lastAlertTimeMsByKey = mutableMapOf<String, Long>()

  @Synchronized
  fun shouldAlert(
    key: String,
    eventTimeMs: Long,
    throttleWindowMs: Long,
  ): Boolean {
    require(throttleWindowMs >= 0L) { "throttleWindowMs must be >= 0" }

    val previousTimeMs = lastAlertTimeMsByKey[key]
    if (throttleWindowMs > 0L && previousTimeMs != null && eventTimeMs <= previousTimeMs + throttleWindowMs) {
      return false
    }

    lastAlertTimeMsByKey[key] = eventTimeMs
    return true
  }
}
