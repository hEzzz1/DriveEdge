package com.driveedge.core.score

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskScoringTest {
  @Test
  fun `fatigue score increases with tiredness signals`() {
    val baseline = BehaviorWindow(
      perclos = 0.1,
      continuousEyeClosureSec = 0.2,
      yawnCount = 0,
      phoneUseSec = 0.0,
      headOffRoadSec = 0.0,
    )
    val risky = baseline.copy(
      perclos = 0.5,
      continuousEyeClosureSec = 1.8,
      yawnCount = 2,
    )

    val scoreA = RiskScoring.score(baseline)
    val scoreB = RiskScoring.score(risky)

    assertTrue(scoreB.fatigueScore > scoreA.fatigueScore)
  }

  @Test
  fun `distraction score caps at one`() {
    val window = BehaviorWindow(
      perclos = 0.2,
      continuousEyeClosureSec = 0.3,
      yawnCount = 0,
      phoneUseSec = 8.0,
      headOffRoadSec = 8.0,
    )

    val score = RiskScoring.score(window)
    assertEquals(1.0, score.distractionScore)
  }
}
