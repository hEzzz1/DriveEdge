package com.driveedge.infer.yolo

data class RawDetection(
  val classId: Int,
  val confidence: Float,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

data class PreprocessedFrame(
  val tensorChw: FloatArray,
  val modelInputWidth: Int,
  val modelInputHeight: Int,
  val scale: Float,
  val padX: Int,
  val padY: Int,
  val originalWidth: Int,
  val originalHeight: Int,
  val timestampMs: Long,
)

interface YoloBackend : AutoCloseable {
  fun load(config: YoloConfig)

  fun infer(frame: PreprocessedFrame): List<RawDetection>

  override fun close() {}
}

class NoOpYoloBackend : YoloBackend {
  override fun load(config: YoloConfig) {
    // Intentionally empty: useful for integration scaffolding.
  }

  override fun infer(frame: PreprocessedFrame): List<RawDetection> = emptyList()
}
