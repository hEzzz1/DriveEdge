package com.driveedge.temporal.engine

data class TemporalEngineConfig(
  val windowSizeMs: Long = 3_000L,
  val blinkMinDurationMs: Long = 80L,
  val blinkMaxDurationMs: Long = 600L,
  val yawnMinDurationMs: Long = 800L,
  val eyeClosedLabels: Set<String> = setOf("eye_closed"),
  val eyeOpenLabels: Set<String> = setOf("eye_open"),
  val yawnLabels: Set<String> = setOf("yawn", "open_mouth"),
  val headDownLabels: Set<String> = setOf("head_down"),
  val headLeftLabels: Set<String> = setOf("head_left"),
  val headRightLabels: Set<String> = setOf("head_right"),
  val headForwardLabels: Set<String> = setOf("head_forward", "head_front", "face_forward"),
) {
  init {
    require(windowSizeMs in 3_000L..10_000L) { "windowSizeMs must be in [3000, 10000]" }
    require(blinkMinDurationMs > 0) { "blinkMinDurationMs must be > 0" }
    require(blinkMaxDurationMs >= blinkMinDurationMs) {
      "blinkMaxDurationMs must be >= blinkMinDurationMs"
    }
    require(yawnMinDurationMs > 0) { "yawnMinDurationMs must be > 0" }
  }
}
