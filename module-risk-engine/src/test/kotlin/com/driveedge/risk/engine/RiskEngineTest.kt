package com.driveedge.risk.engine

import com.driveedge.temporal.engine.FeatureWindow
import com.driveedge.temporal.engine.HeadPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskEngineTest {
  private val riskEngine =
    RiskEngine(
      RiskEngineConfig(
        triggerConfirmCount = 1,
        clearConfirmCount = 1,
        triggerHysteresisDelta = 0.0,
        clearHysteresisDelta = 0.0,
      ),
    )

  @Test
  fun `evaluate triggers fatigue when perclos and yawn rules are met`() {
    val featureWindow = featureWindow(
      windowDurationMs = 4_000L,
      perclos = 0.55,
      blinkRate = 6.0,
      yawnCount = 2,
      headPose = HeadPose.FORWARD,
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertTrue(candidate.fatigueTriggered)
    assertFalse(candidate.distractionTriggered)
    assertTrue(candidate.shouldTrigger)
    assertEquals(RiskType.FATIGUE, candidate.dominantRiskType)
    assertTrue(TriggerReason.FATIGUE_PERCLOS_SUSTAINED in candidate.triggerReasons)
    assertTrue(TriggerReason.FATIGUE_YAWN_FREQUENT in candidate.triggerReasons)
    assertTrue(candidate.fatigueScore > candidate.distractionScore)
    assertTrue(candidate.riskLevel != RiskLevel.NONE)
  }

  @Test
  fun `evaluate triggers distraction for sustained off-road head pose`() {
    val featureWindow = featureWindow(
      windowDurationMs = 3_000L,
      perclos = 0.12,
      blinkRate = 18.0,
      yawnCount = 0,
      headPose = HeadPose.LEFT,
      headPoseStability = 0.82,
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertFalse(candidate.fatigueTriggered)
    assertTrue(candidate.distractionTriggered)
    assertTrue(candidate.shouldTrigger)
    assertEquals(RiskType.DISTRACTION, candidate.dominantRiskType)
    assertTrue(TriggerReason.DISTRACTION_HEAD_OFF_ROAD_SUSTAINED in candidate.triggerReasons)
    assertEquals(RiskLevel.LOW, candidate.riskLevel)
  }

  @Test
  fun `evaluate triggers distraction for sustained head down and gaze offset`() {
    val featureWindow = featureWindow(
      windowDurationMs = 3_000L,
      perclos = 0.10,
      blinkRate = 20.0,
      yawnCount = 0,
      headPose = HeadPose.FORWARD,
      headPitch = 0.82,
      gazeOffset = 0.78,
      headDownDurationMs = 1_800L,
      gazeOffsetDurationMs = 1_700L,
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertFalse(candidate.fatigueTriggered)
    assertTrue(candidate.distractionTriggered)
    assertTrue(candidate.shouldTrigger)
    assertTrue(TriggerReason.DISTRACTION_HEAD_DOWN_SUSTAINED in candidate.triggerReasons)
    assertTrue(TriggerReason.DISTRACTION_GAZE_OFFSET_SUSTAINED in candidate.triggerReasons)
    assertEquals(RiskType.DISTRACTION, candidate.dominantRiskType)
    assertEquals(RiskLevel.LOW, candidate.riskLevel)
    assertTrue(candidate.distractionScore >= 0.40)
  }

  @Test
  fun `evaluate does not trigger off-road distraction when pose stability is below threshold`() {
    val featureWindow = featureWindow(
      windowDurationMs = 3_000L,
      perclos = 0.05,
      blinkRate = 18.0,
      yawnCount = 0,
      headPose = HeadPose.RIGHT,
      headPoseStability = 0.35,
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertFalse(candidate.distractionTriggered)
    assertFalse(TriggerReason.DISTRACTION_HEAD_OFF_ROAD_SUSTAINED in candidate.triggerReasons)
    assertEquals(RiskLevel.NONE, candidate.riskLevel)
  }

  @Test
  fun `evaluate returns none level when no trigger signals`() {
    val featureWindow = featureWindow(
      windowDurationMs = 1_000L,
      perclos = 0.08,
      blinkRate = 18.0,
      yawnCount = 0,
      headPose = HeadPose.FORWARD,
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertFalse(candidate.fatigueTriggered)
    assertFalse(candidate.distractionTriggered)
    assertFalse(candidate.shouldTrigger)
    assertTrue(candidate.triggerReasons.isEmpty())
    assertEquals(RiskLevel.NONE, candidate.riskLevel)
  }

  @Test
  fun `evaluate requires consecutive hits and clears to change trigger state`() {
    val engine =
      RiskEngine(
        RiskEngineConfig(
          lowRiskThreshold = 0.40,
          mediumRiskThreshold = 0.75,
          highRiskThreshold = 0.90,
          triggerConfirmCount = 2,
          clearConfirmCount = 2,
          triggerHysteresisDelta = 0.0,
          clearHysteresisDelta = 0.05,
        ),
      )

    val strongDistraction =
      featureWindow(
        windowDurationMs = 3_000L,
        perclos = 0.08,
        blinkRate = 20.0,
        yawnCount = 0,
        headPose = HeadPose.FORWARD,
        headPitch = 0.82,
        gazeOffset = 0.78,
        headDownDurationMs = 1_800L,
        gazeOffsetDurationMs = 1_700L,
      )
    val weakButSticky =
      featureWindow(
        windowDurationMs = 3_000L,
        perclos = 0.08,
        blinkRate = 20.0,
        yawnCount = 0,
        headPose = HeadPose.FORWARD,
        headPitch = 0.54,
        gazeOffset = 0.54,
        headDownDurationMs = 1_400L,
        gazeOffsetDurationMs = 1_400L,
      )
    val clear =
      featureWindow(
        windowDurationMs = 3_000L,
        perclos = 0.02,
        blinkRate = 18.0,
        yawnCount = 0,
        headPose = HeadPose.FORWARD,
      )

    val first = engine.evaluate(strongDistraction)
    val second = engine.evaluate(strongDistraction)
    val third = engine.evaluate(weakButSticky)
    val fourth = engine.evaluate(clear)
    val fifth = engine.evaluate(clear)

    assertFalse(first.shouldTrigger)
    assertTrue(second.shouldTrigger)
    assertTrue(third.shouldTrigger)
    assertTrue(fourth.shouldTrigger)
    assertFalse(fifth.shouldTrigger)
  }

  private fun featureWindow(
    windowDurationMs: Long,
    perclos: Double,
    blinkRate: Double,
    yawnCount: Int,
    headPose: HeadPose,
    headPitch: Double = 0.0,
    headYaw: Double = 0.0,
    gazeOffset: Double = 0.0,
    headPoseStability: Double = 0.0,
    headDownDurationMs: Long = 0L,
    gazeOffsetDurationMs: Long = 0L,
  ): FeatureWindow =
    FeatureWindow(
      windowStartMs = 10_000L,
      windowEndMs = 10_000L + windowDurationMs,
      windowDurationMs = windowDurationMs,
      perclos = perclos,
      blinkRate = blinkRate,
      yawnCount = yawnCount,
      headPose = headPose,
      headPitch = headPitch,
      headYaw = headYaw,
      gazeOffset = gazeOffset,
      headPoseStability = headPoseStability,
      headDownDurationMs = headDownDurationMs,
      gazeOffsetDurationMs = gazeOffsetDurationMs,
    )
}
