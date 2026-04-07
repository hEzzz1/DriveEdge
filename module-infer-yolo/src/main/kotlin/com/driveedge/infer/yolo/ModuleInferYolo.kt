package com.driveedge.infer.yolo

class ModuleInferYolo(
  private val backend: YoloBackend,
  private val config: YoloConfig,
  private val preprocessor: YoloPreprocessor = YoloPreprocessor(),
  private val postprocessor: YoloPostprocessor = YoloPostprocessor(),
) : AutoCloseable {
  init {
    backend.load(config)
  }

  fun infer(frame: FramePacket): List<DetectionResult> {
    val preprocessed = preprocessor.preprocess(frame, config)
    val rawDetections = backend.infer(preprocessed)
    return postprocessor.process(rawDetections, preprocessed, config)
  }

  override fun close() {
    backend.close()
  }
}
