package com.driveedge.infer.yolo

data class BoundingBox(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  init {
    require(right >= left) { "right must be >= left" }
    require(bottom >= top) { "bottom must be >= top" }
  }

  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top

  fun area(): Float = width * height
}

data class DetectionResult(
  val classId: Int,
  val label: String,
  val confidence: Float,
  val box: BoundingBox,
  val frameTimestampMs: Long,
)
