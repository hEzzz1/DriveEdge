package com.driveedge.infer.yolo

data class YoloConfig(
  val modelPath: String,
  val inputWidth: Int = 640,
  val inputHeight: Int = 640,
  val confidenceThreshold: Float = 0.25f,
  val iouThreshold: Float = 0.45f,
  val maxDetections: Int = 100,
  val classAgnosticNms: Boolean = false,
  val normalizeScale: Float = 1f / 255f,
  val labels: List<String> = DEFAULT_LABELS,
) {
  init {
    require(modelPath.isNotBlank()) { "modelPath must not be blank" }
    require(inputWidth > 0) { "inputWidth must be > 0" }
    require(inputHeight > 0) { "inputHeight must be > 0" }
    require(confidenceThreshold in 0f..1f) { "confidenceThreshold must be in [0,1]" }
    require(iouThreshold in 0f..1f) { "iouThreshold must be in [0,1]" }
    require(maxDetections > 0) { "maxDetections must be > 0" }
    require(normalizeScale > 0f) { "normalizeScale must be > 0" }
  }

  companion object {
    val DEFAULT_LABELS = listOf(
      "closed_mouth",
      "open_mouth",
      "eye_closed",
      "eye_open",
    )
  }
}
