package com.driveedge.event.center

data class EventCenterConfig
  @JvmOverloads
  constructor(
  val vehicleId: String,
  val fleetId: String? = null,
  val driverId: String? = null,
  val algorithmVersion: String = "unknown",
  val debounceWindowMs: Long = 5_000L,
) {
  init {
    require(vehicleId.isNotBlank()) { "vehicleId must not be blank" }
    require(debounceWindowMs >= 0) { "debounceWindowMs must be >= 0" }
  }
}
