package com.driveedge.alert

import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType

enum class AlertExecutionStatus {
  EXECUTED,
  THROTTLED,
  SKIPPED_NO_CHANNEL,
}

data class AlertExecutionRecord(
  val eventId: String,
  val eventTimeMs: Long,
  val vehicleId: String,
  val riskLevel: RiskLevel,
  val dominantRiskType: RiskType?,
  val throttleKey: String,
  val throttleWindowMs: Long,
  val status: AlertExecutionStatus,
  val attemptedChannels: Set<AlertChannel>,
  val succeededChannels: Set<AlertChannel>,
  val failedChannels: Set<AlertChannel>,
  val message: String,
  val executedAtMs: Long,
)
