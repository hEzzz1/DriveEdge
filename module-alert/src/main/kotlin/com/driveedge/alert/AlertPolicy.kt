package com.driveedge.alert

import com.driveedge.risk.engine.RiskLevel

data class AlertPolicy(
  val channels: Set<AlertChannel>,
  val throttleWindowMs: Long,
) {
  init {
    require(throttleWindowMs >= 0L) { "throttleWindowMs must be >= 0" }
  }
}

data class AlertCenterConfig(
  val policyByRiskLevel: Map<RiskLevel, AlertPolicy> = defaultPolicies(),
) {
  fun policyOf(riskLevel: RiskLevel): AlertPolicy =
    policyByRiskLevel[riskLevel]
      ?: AlertPolicy(channels = emptySet(), throttleWindowMs = 0L)

  companion object {
    fun defaultPolicies(): Map<RiskLevel, AlertPolicy> =
      mapOf(
        RiskLevel.NONE to AlertPolicy(channels = emptySet(), throttleWindowMs = 0L),
        RiskLevel.LOW to AlertPolicy(channels = setOf(AlertChannel.UI_PROMPT), throttleWindowMs = 8_000L),
        RiskLevel.MEDIUM to AlertPolicy(
          channels = setOf(AlertChannel.SOUND, AlertChannel.UI_PROMPT),
          throttleWindowMs = 5_000L,
        ),
        RiskLevel.HIGH to AlertPolicy(
          channels = setOf(AlertChannel.SOUND, AlertChannel.VIBRATION, AlertChannel.UI_PROMPT),
          throttleWindowMs = 3_000L,
        ),
      )
  }
}
