package com.driveedge.temporal.engine

enum class HeadPose {
  FORWARD,
  DOWN,
  LEFT,
  RIGHT,
  UNKNOWN,
}

data class FeatureWindow(
  val windowStartMs: Long,
  val windowEndMs: Long,
  val windowDurationMs: Long,
  val perclos: Double,
  val blinkRate: Double,
  val yawnCount: Int,
  val headPose: HeadPose,
)
