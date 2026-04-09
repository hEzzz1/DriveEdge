package com.driveedge.risk.engine

data class FatigueWeights(
  val perclosWeight: Double = 0.65,
  val yawnWeight: Double = 0.20,
  val blinkWeight: Double = 0.15,
  val yawnNormalizationCount: Double = 3.0,
  val blinkHealthyRatePerMin: Double = 15.0,
)

data class DistractionWeights(
  val offRoadWeight: Double = 0.85,
  val unknownPoseWeight: Double = 0.15,
)

data class RiskEngineConfig(
  val fatigueWeights: FatigueWeights = FatigueWeights(),
  val distractionWeights: DistractionWeights = DistractionWeights(),
  val fatiguePerclosThreshold: Double = 0.40,
  val fatiguePerclosDurationMs: Long = 3_000L,
  val fatigueYawnCountThreshold: Int = 2,
  val fatigueYawnWindowMaxMs: Long = 30_000L,
  val distractionHeadPoseDurationMs: Long = 2_000L,
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
    require(lowRiskThreshold in 0.0..1.0) { "lowRiskThreshold must be in [0, 1]" }
    require(mediumRiskThreshold in 0.0..1.0) { "mediumRiskThreshold must be in [0, 1]" }
    require(highRiskThreshold in 0.0..1.0) { "highRiskThreshold must be in [0, 1]" }
    require(lowRiskThreshold <= mediumRiskThreshold) { "lowRiskThreshold must be <= mediumRiskThreshold" }
    require(mediumRiskThreshold <= highRiskThreshold) { "mediumRiskThreshold must be <= highRiskThreshold" }
    require(fatigueWeights.yawnNormalizationCount > 0.0) { "yawnNormalizationCount must be > 0" }
    require(fatigueWeights.blinkHealthyRatePerMin > 0.0) { "blinkHealthyRatePerMin must be > 0" }
  }
}
