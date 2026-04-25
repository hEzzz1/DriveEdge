package com.driveedge.risk.engine

import com.driveedge.temporal.engine.FeatureWindow
import com.driveedge.temporal.engine.HeadPose
import kotlin.math.max
import kotlin.jvm.JvmOverloads

class RiskEngine @JvmOverloads constructor(
  private val config: RiskEngineConfig = RiskEngineConfig(),
) {
  private var triggerStreak: Int = 0
  private var clearStreak: Int = 0
  private var latchedRiskLevel: RiskLevel = RiskLevel.NONE
  private var latchedDominantRiskType: RiskType? = null
  private var latchedTriggerReasons: Set<TriggerReason> = emptySet()

  fun evaluate(featureWindow: FeatureWindow): RiskEventCandidate {
    val fatigueScore = computeFatigueScore(featureWindow)
    val distractionScore = computeDistractionScore(featureWindow)
    val rawScore = max(fatigueScore, distractionScore)
    val baseRiskLevel = toRiskLevel(rawScore)

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
    val distractionHeadDownTriggered = isDistractionHeadDownTriggered(featureWindow)
    if (distractionHeadDownTriggered) {
      triggerReasons += TriggerReason.DISTRACTION_HEAD_DOWN_SUSTAINED
    }
    val distractionGazeOffsetTriggered = isDistractionGazeOffsetTriggered(featureWindow)
    if (distractionGazeOffsetTriggered) {
      triggerReasons += TriggerReason.DISTRACTION_GAZE_OFFSET_SUSTAINED
    }

    val fatigueTriggered = fatiguePerclosTriggered || fatigueYawnTriggered
    val distractionTriggered =
      distractionHeadPoseTriggered || distractionHeadDownTriggered || distractionGazeOffsetTriggered
    val rawShouldTrigger = fatigueTriggered || distractionTriggered
    val rawDominantRiskType = dominantRiskType(fatigueScore, distractionScore)
    val shouldTrigger = stabilizeTriggerState(rawShouldTrigger, rawScore)
    val riskLevel = stabilizeRiskLevel(shouldTrigger, baseRiskLevel, rawScore, rawDominantRiskType)
    val dominantRiskType =
      when {
        rawShouldTrigger -> rawDominantRiskType
        shouldTrigger -> latchedDominantRiskType
        else -> null
      }
    val stabilizedReasons =
      when {
        rawShouldTrigger -> {
          latchedTriggerReasons = triggerReasons
          triggerReasons
        }
        shouldTrigger -> latchedTriggerReasons
        else -> emptySet()
      }

    return RiskEventCandidate(
      windowStartMs = featureWindow.windowStartMs,
      windowEndMs = featureWindow.windowEndMs,
      fatigueScore = fatigueScore,
      distractionScore = distractionScore,
      riskLevel = riskLevel,
      dominantRiskType = dominantRiskType,
      fatigueTriggered = fatigueTriggered,
      distractionTriggered = distractionTriggered,
      shouldTrigger = shouldTrigger,
      triggerReasons = stabilizedReasons,
    )
  }

  fun reset() {
    triggerStreak = 0
    clearStreak = 0
    latchedRiskLevel = RiskLevel.NONE
    latchedDominantRiskType = null
    latchedTriggerReasons = emptySet()
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
      featureWindow.windowDurationMs >= config.distractionHeadPoseDurationMs &&
      featureWindow.headPoseStability >= config.distractionHeadPoseStabilityThreshold

  private fun isDistractionHeadDownTriggered(featureWindow: FeatureWindow): Boolean =
    featureWindow.headPitch >= config.distractionHeadDownThreshold &&
      featureWindow.headDownDurationMs >= config.distractionHeadDownDurationMs

  private fun isDistractionGazeOffsetTriggered(featureWindow: FeatureWindow): Boolean =
    featureWindow.gazeOffset >= config.distractionGazeOffsetThreshold &&
      featureWindow.gazeOffsetDurationMs >= config.distractionGazeOffsetDurationMs

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
    val offRoadDurationFactor = ratio(
      featureWindow.windowDurationMs.toDouble(),
      config.distractionHeadPoseDurationMs.toDouble(),
    )
    val offRoadSignal =
      if (featureWindow.headPose in OFF_ROAD_POSES) {
        ratio(featureWindow.headPoseStability, config.distractionHeadPoseStabilityThreshold)
      } else {
        0.0
      }
    val headDownSignal =
      ratio(featureWindow.headPitch, config.distractionHeadDownThreshold) *
        ratio(featureWindow.headDownDurationMs.toDouble(), config.distractionHeadDownDurationMs.toDouble())
    val gazeOffsetSignal =
      ratio(featureWindow.gazeOffset, config.distractionGazeOffsetThreshold) *
        ratio(featureWindow.gazeOffsetDurationMs.toDouble(), config.distractionGazeOffsetDurationMs.toDouble())
    val unknownSignal = if (featureWindow.headPose == HeadPose.UNKNOWN) offRoadDurationFactor else 0.0

    return weightedAverage(
      values = listOf(
        (offRoadSignal * offRoadDurationFactor) to config.distractionWeights.offRoadWeight,
        headDownSignal to config.distractionWeights.headDownWeight,
        gazeOffsetSignal to config.distractionWeights.gazeOffsetWeight,
        unknownSignal to config.distractionWeights.unknownPoseWeight,
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

  private fun stabilizeTriggerState(
    rawShouldTrigger: Boolean,
    rawScore: Double,
  ): Boolean {
    val keepTriggered =
      latchedRiskLevel != RiskLevel.NONE && rawScore >= (config.lowRiskThreshold - config.clearHysteresisDelta)
    val effectivePositive = rawShouldTrigger || keepTriggered

    if (effectivePositive) {
      triggerStreak += 1
      clearStreak = 0
      if (latchedRiskLevel == RiskLevel.NONE && triggerStreak >= config.triggerConfirmCount) {
        latchedRiskLevel = RiskLevel.LOW
      }
    } else {
      clearStreak += 1
      triggerStreak = 0
      if (latchedRiskLevel != RiskLevel.NONE && clearStreak >= config.clearConfirmCount) {
        latchedRiskLevel = RiskLevel.NONE
        latchedDominantRiskType = null
        latchedTriggerReasons = emptySet()
      }
    }

    return latchedRiskLevel != RiskLevel.NONE
  }

  private fun stabilizeRiskLevel(
    shouldTrigger: Boolean,
    rawRiskLevel: RiskLevel,
    rawScore: Double,
    rawDominantRiskType: RiskType?,
  ): RiskLevel {
    if (!shouldTrigger) {
      latchedRiskLevel = RiskLevel.NONE
      return RiskLevel.NONE
    }

    var level = if (latchedRiskLevel == RiskLevel.NONE) RiskLevel.LOW else latchedRiskLevel
    val targetLevel = if (rawRiskLevel == RiskLevel.NONE) RiskLevel.LOW else rawRiskLevel

    while (level.ordinal < targetLevel.ordinal) {
      val next = RiskLevel.entries[level.ordinal + 1]
      if (rawScore >= thresholdOf(next) + config.triggerHysteresisDelta) {
        level = next
      } else {
        break
      }
    }

    while (level.ordinal > targetLevel.ordinal) {
      if (rawScore <= thresholdOf(level) - config.clearHysteresisDelta) {
        level = RiskLevel.entries[level.ordinal - 1]
      } else {
        break
      }
    }

    latchedRiskLevel = level
    if (level != RiskLevel.NONE && rawDominantRiskType != null) {
      latchedDominantRiskType = rawDominantRiskType
    }
    return level
  }

  private fun thresholdOf(level: RiskLevel): Double =
    when (level) {
      RiskLevel.NONE -> 0.0
      RiskLevel.LOW -> config.lowRiskThreshold
      RiskLevel.MEDIUM -> config.mediumRiskThreshold
      RiskLevel.HIGH -> config.highRiskThreshold
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
