package com.driveedge.risk.engine

import com.driveedge.temporal.engine.FeatureWindow
import com.driveedge.temporal.engine.HeadPose
import kotlin.math.max

class RiskEngine(
  private val config: RiskEngineConfig = RiskEngineConfig(),
) {
  fun evaluate(featureWindow: FeatureWindow): RiskEventCandidate {
    val fatigueScore = computeFatigueScore(featureWindow)
    val distractionScore = computeDistractionScore(featureWindow)
    val baseRiskLevel = toRiskLevel(max(fatigueScore, distractionScore))

    val triggerReasons = linkedSetOf<TriggerReason>()

    val fatiguePerclosTriggered = isFatiguePerclosTriggered(featureWindow)
    if (fatiguePerclosTriggered) {
      triggerReasons += TriggerReason.FATIGUE_PERCLOS_SUSTAINED
    }

    val fatigueYawnTriggered = isFatigueYawnTriggered(featureWindow)
    if (fatigueYawnTriggered) {
      triggerReasons += TriggerReason.FATIGUE_YAWN_FREQUENT
    }

    val distractionHeadPoseTriggered = isDistractionHeadPoseTriggered(featureWindow)
    if (distractionHeadPoseTriggered) {
      triggerReasons += TriggerReason.DISTRACTION_HEAD_OFF_ROAD_SUSTAINED
    }

    val fatigueTriggered = fatiguePerclosTriggered || fatigueYawnTriggered
    val distractionTriggered = distractionHeadPoseTriggered
    val shouldTrigger = fatigueTriggered || distractionTriggered
    val riskLevel =
      if (shouldTrigger && baseRiskLevel == RiskLevel.NONE) {
        RiskLevel.LOW
      } else {
        baseRiskLevel
      }

    return RiskEventCandidate(
      windowStartMs = featureWindow.windowStartMs,
      windowEndMs = featureWindow.windowEndMs,
      fatigueScore = fatigueScore,
      distractionScore = distractionScore,
      riskLevel = riskLevel,
      dominantRiskType = dominantRiskType(fatigueScore, distractionScore),
      fatigueTriggered = fatigueTriggered,
      distractionTriggered = distractionTriggered,
      shouldTrigger = shouldTrigger,
      triggerReasons = triggerReasons,
    )
  }

  private fun dominantRiskType(
    fatigueScore: Double,
    distractionScore: Double,
  ): RiskType? {
    if (fatigueScore <= 0.0 && distractionScore <= 0.0) {
      return null
    }
    return if (fatigueScore >= distractionScore) RiskType.FATIGUE else RiskType.DISTRACTION
  }

  private fun isFatiguePerclosTriggered(featureWindow: FeatureWindow): Boolean =
    featureWindow.perclos >= config.fatiguePerclosThreshold &&
      featureWindow.windowDurationMs >= config.fatiguePerclosDurationMs

  private fun isFatigueYawnTriggered(featureWindow: FeatureWindow): Boolean =
    featureWindow.yawnCount >= config.fatigueYawnCountThreshold &&
      featureWindow.windowDurationMs <= config.fatigueYawnWindowMaxMs

  private fun isDistractionHeadPoseTriggered(featureWindow: FeatureWindow): Boolean =
    featureWindow.headPose in OFF_ROAD_POSES &&
      featureWindow.windowDurationMs >= config.distractionHeadPoseDurationMs

  private fun computeFatigueScore(featureWindow: FeatureWindow): Double {
    val durationFactor = ratio(
      featureWindow.windowDurationMs.toDouble(),
      config.fatiguePerclosDurationMs.toDouble(),
    )
    val perclosComponent = clamp01(featureWindow.perclos) * durationFactor
    val yawnComponent = ratio(
      featureWindow.yawnCount.toDouble(),
      config.fatigueWeights.yawnNormalizationCount,
    )
    val blinkFatigueComponent = 1.0 - ratio(
      featureWindow.blinkRate,
      config.fatigueWeights.blinkHealthyRatePerMin,
    )

    return weightedAverage(
      values = listOf(
        perclosComponent to config.fatigueWeights.perclosWeight,
        yawnComponent to config.fatigueWeights.yawnWeight,
        blinkFatigueComponent to config.fatigueWeights.blinkWeight,
      ),
    )
  }

  private fun computeDistractionScore(featureWindow: FeatureWindow): Double {
    val durationFactor = ratio(
      featureWindow.windowDurationMs.toDouble(),
      config.distractionHeadPoseDurationMs.toDouble(),
    )
    val offRoadSignal = if (featureWindow.headPose in OFF_ROAD_POSES) 1.0 else 0.0
    val unknownSignal = if (featureWindow.headPose == HeadPose.UNKNOWN) 1.0 else 0.0

    return weightedAverage(
      values = listOf(
        (offRoadSignal * durationFactor) to config.distractionWeights.offRoadWeight,
        (unknownSignal * durationFactor) to config.distractionWeights.unknownPoseWeight,
      ),
    )
  }

  private fun toRiskLevel(score: Double): RiskLevel =
    when {
      score >= config.highRiskThreshold -> RiskLevel.HIGH
      score >= config.mediumRiskThreshold -> RiskLevel.MEDIUM
      score >= config.lowRiskThreshold -> RiskLevel.LOW
      else -> RiskLevel.NONE
    }

  private fun weightedAverage(values: List<Pair<Double, Double>>): Double {
    val weightSum = values.sumOf { (_, weight) -> max(0.0, weight) }
    if (weightSum <= 0.0) {
      return 0.0
    }
    val weightedSum = values.sumOf { (value, weight) -> clamp01(value) * max(0.0, weight) }
    return clamp01(weightedSum / weightSum)
  }

  private fun ratio(value: Double, denominator: Double): Double {
    if (denominator <= 0.0) {
      return 0.0
    }
    return clamp01(value / denominator)
  }

  private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

  private companion object {
    val OFF_ROAD_POSES: Set<HeadPose> = setOf(HeadPose.DOWN, HeadPose.LEFT, HeadPose.RIGHT)
  }
}
