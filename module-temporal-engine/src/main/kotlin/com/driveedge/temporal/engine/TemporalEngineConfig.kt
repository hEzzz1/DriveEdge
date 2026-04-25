package com.driveedge.temporal.engine

import kotlin.jvm.JvmOverloads

data class TemporalEngineConfig @JvmOverloads constructor(
  val windowSizeMs: Long = 3_000L,
  val smoothingWindowCount: Int = 3,
  val stableWindowHitCount: Int = 2,
  val clearWindowCount: Int = 2,
  val featureEmaAlpha: Double = 0.65,
  val blinkMinDurationMs: Long = 80L,
  val blinkMaxDurationMs: Long = 600L,
  val yawnMinDurationMs: Long = 800L,
  val minSignalConfidence: Float = 0.5f,
  val headDownThreshold: Double = 0.55,
  val gazeOffsetThreshold: Double = 0.55,
  val headPoseStabilityThreshold: Double = 0.60,
  val headDownMinDurationMs: Long = 1_500L,
  val gazeOffsetMinDurationMs: Long = 1_500L,
  val eyeClosedLabels: Set<String> = setOf("eye_closed"),
  val eyeOpenLabels: Set<String> = setOf("eye_open"),
  val yawnLabels: Set<String> = setOf("yawn", "open_mouth"),
  val headDownLabels: Set<String> = setOf("head_down"),
  val headLeftLabels: Set<String> = setOf("head_left"),
  val headRightLabels: Set<String> = setOf("head_right"),
  val headForwardLabels: Set<String> = setOf("head_forward", "head_front", "face_forward"),
  val gazeLeftLabels: Set<String> = setOf("gaze_left", "look_left"),
  val gazeRightLabels: Set<String> = setOf("gaze_right", "look_right"),
  val gazeDownLabels: Set<String> = setOf("gaze_down", "look_down"),
  val gazeForwardLabels: Set<String> = setOf("gaze_forward", "look_forward"),
) {
  init {
    require(windowSizeMs in 3_000L..10_000L) { "windowSizeMs must be in [3000, 10000]" }
    require(smoothingWindowCount > 0) { "smoothingWindowCount must be > 0" }
    require(stableWindowHitCount > 0) { "stableWindowHitCount must be > 0" }
    require(clearWindowCount > 0) { "clearWindowCount must be > 0" }
    require(featureEmaAlpha in 0.0..1.0) { "featureEmaAlpha must be in [0, 1]" }
    require(blinkMinDurationMs > 0) { "blinkMinDurationMs must be > 0" }
    require(blinkMaxDurationMs >= blinkMinDurationMs) {
      "blinkMaxDurationMs must be >= blinkMinDurationMs"
    }
    require(yawnMinDurationMs > 0) { "yawnMinDurationMs must be > 0" }
    require(minSignalConfidence in 0.0f..1.0f) { "minSignalConfidence must be in [0, 1]" }
    require(headDownThreshold in 0.0..1.0) { "headDownThreshold must be in [0, 1]" }
    require(gazeOffsetThreshold in 0.0..1.0) { "gazeOffsetThreshold must be in [0, 1]" }
    require(headPoseStabilityThreshold in 0.0..1.0) { "headPoseStabilityThreshold must be in [0, 1]" }
    require(headDownMinDurationMs > 0L) { "headDownMinDurationMs must be > 0" }
    require(gazeOffsetMinDurationMs > 0L) { "gazeOffsetMinDurationMs must be > 0" }
  }
}
