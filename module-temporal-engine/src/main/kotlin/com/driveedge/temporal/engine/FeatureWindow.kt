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
  val headPitch: Double = 0.0,
  val headYaw: Double = 0.0,
  val gazeOffset: Double = 0.0,
  val headPoseStability: Double = 0.0,
  val headDownDurationMs: Long = 0L,
  val gazeOffsetDurationMs: Long = 0L,
)
