package com.driveedge.risk.engine

import kotlin.jvm.JvmOverloads

data class FatigueWeights(
  val perclosWeight: Double = 0.65,
  val yawnWeight: Double = 0.20,
  val blinkWeight: Double = 0.15,
  val yawnNormalizationCount: Double = 3.0,
  val blinkHealthyRatePerMin: Double = 15.0,
)

data class DistractionWeights(
  val offRoadWeight: Double = 0.45,
  val headDownWeight: Double = 0.35,
  val gazeOffsetWeight: Double = 0.10,
  val unknownPoseWeight: Double = 0.15,
)

data class RiskEngineConfig @JvmOverloads constructor(
  val fatigueWeights: FatigueWeights = FatigueWeights(),
  val distractionWeights: DistractionWeights = DistractionWeights(),
  val fatiguePerclosThreshold: Double = 0.40,
  val fatiguePerclosDurationMs: Long = 3_000L,
  val fatigueYawnCountThreshold: Int = 2,
  val fatigueYawnWindowMaxMs: Long = 30_000L,
  val distractionHeadPoseDurationMs: Long = 2_000L,
  val distractionHeadPoseStabilityThreshold: Double = 0.60,
  val distractionHeadDownThreshold: Double = 0.55,
  val distractionHeadDownDurationMs: Long = 1_500L,
  val distractionGazeOffsetThreshold: Double = 0.55,
  val distractionGazeOffsetDurationMs: Long = 1_500L,
  val triggerConfirmCount: Int = 2,
  val clearConfirmCount: Int = 2,
  val triggerHysteresisDelta: Double = 0.03,
  val clearHysteresisDelta: Double = 0.08,
  val lowRiskThreshold: Double = 0.60,
  val mediumRiskThreshold: Double = 0.75,
  val highRiskThreshold: Double = 0.85,
) {
  init {
    require(fatiguePerclosThreshold in 0.0..1.0) { "fatiguePerclosThreshold must be in [0, 1]" }
    require(fatiguePerclosDurationMs > 0L) { "fatiguePerclosDurationMs must be > 0" }
    require(fatigueYawnCountThreshold >= 0) { "fatigueYawnCountThreshold must be >= 0" }
    require(fatigueYawnWindowMaxMs > 0L) { "fatigueYawnWindowMaxMs must be > 0" }
    require(distractionHeadPoseDurationMs > 0L) { "distractionHeadPoseDurationMs must be > 0" }
    require(distractionHeadPoseStabilityThreshold in 0.0..1.0) {
      "distractionHeadPoseStabilityThreshold must be in [0, 1]"
    }
    require(distractionHeadDownThreshold in 0.0..1.0) { "distractionHeadDownThreshold must be in [0, 1]" }
    require(distractionHeadDownDurationMs > 0L) { "distractionHeadDownDurationMs must be > 0" }
    require(distractionGazeOffsetThreshold in 0.0..1.0) { "distractionGazeOffsetThreshold must be in [0, 1]" }
    require(distractionGazeOffsetDurationMs > 0L) { "distractionGazeOffsetDurationMs must be > 0" }
    require(triggerConfirmCount > 0) { "triggerConfirmCount must be > 0" }
    require(clearConfirmCount > 0) { "clearConfirmCount must be > 0" }
    require(triggerHysteresisDelta in 0.0..1.0) { "triggerHysteresisDelta must be in [0, 1]" }
    require(clearHysteresisDelta in 0.0..1.0) { "clearHysteresisDelta must be in [0, 1]" }
    require(lowRiskThreshold in 0.0..1.0) { "lowRiskThreshold must be in [0, 1]" }
    require(mediumRiskThreshold in 0.0..1.0) { "mediumRiskThreshold must be in [0, 1]" }
    require(highRiskThreshold in 0.0..1.0) { "highRiskThreshold must be in [0, 1]" }
    require(lowRiskThreshold <= mediumRiskThreshold) { "lowRiskThreshold must be <= mediumRiskThreshold" }
    require(mediumRiskThreshold <= highRiskThreshold) { "mediumRiskThreshold must be <= highRiskThreshold" }
    require(fatigueWeights.yawnNormalizationCount > 0.0) { "yawnNormalizationCount must be > 0" }
    require(fatigueWeights.blinkHealthyRatePerMin > 0.0) { "blinkHealthyRatePerMin must be > 0" }
    require(distractionWeights.offRoadWeight >= 0.0) { "offRoadWeight must be >= 0" }
    require(distractionWeights.headDownWeight >= 0.0) { "headDownWeight must be >= 0" }
    require(distractionWeights.gazeOffsetWeight >= 0.0) { "gazeOffsetWeight must be >= 0" }
    require(distractionWeights.unknownPoseWeight >= 0.0) { "unknownPoseWeight must be >= 0" }
  }
}
