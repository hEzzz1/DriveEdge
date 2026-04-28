package com.driveedge.event.center

data class EventCenterConfig
  @JvmOverloads
  constructor(
  val deviceCode: String,
  val vehicleId: String,
  val enterpriseId: String? = null,
  val fleetId: String? = null,
  val driverId: String? = null,
  val sessionId: Long? = null,
  val configVersion: String? = null,
  val algorithmVersion: String = "unknown",
  val debounceWindowMs: Long = 5_000L,
) {
  init {
    require(deviceCode.isNotBlank()) { "deviceCode must not be blank" }
    require(vehicleId.isNotBlank()) { "vehicleId must not be blank" }
    require(debounceWindowMs >= 0) { "debounceWindowMs must be >= 0" }
  }
}
