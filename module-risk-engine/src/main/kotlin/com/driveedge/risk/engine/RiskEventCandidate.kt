package com.driveedge.risk.engine

enum class RiskLevel {
  NONE,
  LOW,
  MEDIUM,
  HIGH,
}

enum class RiskType {
  FATIGUE,
  DISTRACTION,
}

enum class TriggerReason {
  FATIGUE_PERCLOS_SUSTAINED,
  FATIGUE_YAWN_FREQUENT,
  DISTRACTION_HEAD_OFF_ROAD_SUSTAINED,
  DISTRACTION_HEAD_DOWN_SUSTAINED,
  DISTRACTION_GAZE_OFFSET_SUSTAINED,
}

data class RiskEventCandidate(
  val windowStartMs: Long,
  val windowEndMs: Long,
  val fatigueScore: Double,
  val distractionScore: Double,
  val riskLevel: RiskLevel,
  val dominantRiskType: RiskType?,
  val fatigueTriggered: Boolean,
  val distractionTriggered: Boolean,
  val shouldTrigger: Boolean,
  val triggerReasons: Set<TriggerReason>,
)
