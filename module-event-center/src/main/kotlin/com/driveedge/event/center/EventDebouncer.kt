package com.driveedge.event.center

class EventDebouncer(
  private val debounceWindowMs: Long,
) {
  private val lastEventTimeMsByKey = mutableMapOf<String, Long>()

  @Synchronized
  fun shouldEmit(
    key: String,
    eventTimeMs: Long,
  ): Boolean {
    val previousTimeMs = lastEventTimeMsByKey[key]
    if (previousTimeMs != null && eventTimeMs <= previousTimeMs + debounceWindowMs) {
      return false
    }

    lastEventTimeMsByKey[key] = eventTimeMs
    return true
  }
}
