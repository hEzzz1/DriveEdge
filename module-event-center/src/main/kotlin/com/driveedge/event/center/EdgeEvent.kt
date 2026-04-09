package com.driveedge.event.center

import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason

enum class UploadStatus {
  PENDING,
  SENDING,
  SUCCESS,
  RETRY_WAIT,
  FAILED_FINAL,
}

data class EdgeEvent(
  val eventId: String,
  val fleetId: String?,
  val vehicleId: String,
  val driverId: String?,
  val eventTimeUtc: String,
  val fatigueScore: Double,
  val distractionScore: Double,
  val riskLevel: RiskLevel,
  val dominantRiskType: RiskType?,
  val triggerReasons: Set<TriggerReason>,
  val algorithmVer: String,
  val uploadStatus: UploadStatus = UploadStatus.PENDING,
  val windowStartMs: Long,
  val windowEndMs: Long,
  val createdAtMs: Long,
)
