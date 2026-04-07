package com.driveedge.core.score

data class BehaviorWindow(
  val perclos: Double,
  val continuousEyeClosureSec: Double,
  val yawnCount: Int,
  val phoneUseSec: Double,
  val headOffRoadSec: Double,
)

data class RiskScore(
  val fatigueScore: Double,
  val distractionScore: Double,
)

object RiskScoring {
  fun score(window: BehaviorWindow): RiskScore {
    val fatigue = normalize(
      0.5 * window.perclos +
        0.3 * (window.continuousEyeClosureSec / 3.0) +
        0.2 * (window.yawnCount / 3.0),
    )

    val distraction = normalize(
      0.6 * (window.phoneUseSec / 2.0) +
        0.4 * (window.headOffRoadSec / 2.0),
    )

    return RiskScore(
      fatigueScore = fatigue,
      distractionScore = distraction,
    )
  }

  private fun normalize(value: Double): Double = value.coerceIn(0.0, 1.0)
}
