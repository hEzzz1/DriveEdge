package com.driveedge.infer.yolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModuleInferYoloTest {
  @Test
  fun `preprocess keeps aspect ratio and applies letterbox padding`() {
    val frame = FramePacket(
      width = 2,
      height = 1,
      data = byteArrayOf(
        127, 0, 0,
        0, 127, 0,
      ),
      timestampMs = 1L,
    )
    val config = YoloConfig(
      modelPath = "models/deploy/yolo.onnx",
      inputWidth = 4,
      inputHeight = 4,
      normalizeScale = 1f / 127f,
    )

    val preprocessed = YoloPreprocessor().preprocess(frame, config)
    val plane = config.inputWidth * config.inputHeight

    assertEquals(2f, preprocessed.scale)
    assertEquals(0, preprocessed.padX)
    assertEquals(1, preprocessed.padY)

    // Top padding row should remain zero.
    assertEquals(0f, preprocessed.tensorChw[0])

    // Pixel at (0,1) comes from first source pixel (R channel = 127 -> 1.0 after normalize).
    val idxR = 1 * config.inputWidth + 0
    assertEquals(1f, preprocessed.tensorChw[idxR])
    assertEquals(0f, preprocessed.tensorChw[plane + idxR])
    assertEquals(0f, preprocessed.tensorChw[(plane * 2) + idxR])
  }

  @Test
  fun `postprocess applies class-aware nms`() {
    val frameMeta = PreprocessedFrame(
      tensorChw = FloatArray(3 * 10 * 10),
      modelInputWidth = 10,
      modelInputHeight = 10,
      scale = 1f,
      padX = 0,
      padY = 0,
      originalWidth = 10,
      originalHeight = 10,
      timestampMs = 10L,
    )
    val config = YoloConfig(
      modelPath = "models/deploy/yolo.onnx",
      iouThreshold = 0.5f,
      confidenceThreshold = 0.1f,
      labels = listOf("closed_mouth", "open_mouth", "eye_closed", "eye_open"),
    )
    val raw = listOf(
      RawDetection(0, 0.95f, 1f, 1f, 6f, 6f),
      RawDetection(0, 0.85f, 1.5f, 1.5f, 6f, 6f),
      RawDetection(1, 0.90f, 1.5f, 1.5f, 6f, 6f),
    )

    val results = YoloPostprocessor().process(raw, frameMeta, config)

    assertEquals(2, results.size)
    assertEquals(0, results[0].classId)
    assertEquals("closed_mouth", results[0].label)
    assertEquals(1, results[1].classId)
    assertEquals("open_mouth", results[1].label)
  }

  @Test
  fun `module infer maps box from model space back to original frame`() {
    val backend = FakeBackend(
      raw = listOf(
        // Frame is 100x50, model input is 100x100, so padY = 25.
        RawDetection(
          classId = 2,
          confidence = 0.88f,
          left = 10f,
          top = 35f,
          right = 60f,
          bottom = 70f,
        ),
      ),
    )
    val module = ModuleInferYolo(
      backend = backend,
      config = YoloConfig(
        modelPath = "/Users/m1ngyangg/Downloads/best.pt",
        inputWidth = 100,
        inputHeight = 100,
      ),
    )
    val frame = FramePacket(
      width = 100,
      height = 50,
      data = ByteArray(100 * 50 * 3),
      timestampMs = 1234L,
    )

    val detections = module.infer(frame)

    assertTrue(backend.loaded)
    assertEquals(1, detections.size)
    val hit = detections.firstOrNull()
    assertNotNull(hit)
    assertEquals(2, hit.classId)
    assertEquals("eye_closed", hit.label)
    assertEquals(10f, hit.box.left)
    assertEquals(10f, hit.box.top)
    assertEquals(60f, hit.box.right)
    assertEquals(45f, hit.box.bottom)
    assertEquals(1234L, hit.frameTimestampMs)
    module.close()
  }
}

private class FakeBackend(
  private val raw: List<RawDetection>,
) : YoloBackend {
  var loaded: Boolean = false
    private set

  override fun load(config: YoloConfig) {
    loaded = true
  }

  override fun infer(frame: PreprocessedFrame): List<RawDetection> = raw
}
