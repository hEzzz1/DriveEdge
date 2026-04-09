package com.driveedge.risk.engine

import com.driveedge.temporal.engine.FeatureWindow
import com.driveedge.temporal.engine.HeadPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskEngineTest {
  private val riskEngine = RiskEngine()

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
    )

    val candidate = riskEngine.evaluate(featureWindow)

    assertFalse(candidate.fatigueTriggered)
    assertTrue(candidate.distractionTriggered)
    assertTrue(candidate.shouldTrigger)
    assertEquals(RiskType.DISTRACTION, candidate.dominantRiskType)
    assertTrue(TriggerReason.DISTRACTION_HEAD_OFF_ROAD_SUSTAINED in candidate.triggerReasons)
    assertEquals(RiskLevel.HIGH, candidate.riskLevel)
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

  private fun featureWindow(
    windowDurationMs: Long,
    perclos: Double,
    blinkRate: Double,
    yawnCount: Int,
    headPose: HeadPose,
  ): FeatureWindow =
    FeatureWindow(
      windowStartMs = 10_000L,
      windowEndMs = 10_000L + windowDurationMs,
      windowDurationMs = windowDurationMs,
      perclos = perclos,
      blinkRate = blinkRate,
      yawnCount = yawnCount,
      headPose = headPose,
    )
}
