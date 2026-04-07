package com.driveedge.temporal.engine

import com.driveedge.infer.yolo.BoundingBox
import com.driveedge.infer.yolo.DetectionResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalEngineTest {
  @Test
  fun `extract computes perclos blinkRate yawnCount and dominant head pose`() {
    val detections = mutableListOf<DetectionResult>()
    for (idx in 0 until 30) {
      val ts = idx * 100L
      val eyeLabel = if (idx in setOf(10, 11, 20, 21)) "eye_closed" else "eye_open"
      detections += detection(eyeLabel, ts)

      if (idx in 5..13 || idx in 18..26) {
        detections += detection("open_mouth", ts, confidence = 0.85f)
      }

      if (idx <= 20) {
        detections += detection("head_left", ts, confidence = 0.80f)
      } else {
        detections += detection("head_down", ts, confidence = 0.75f)
      }
    }

    val window = TemporalFeatureExtractor.extract(detections)

    assertEquals(0L, window.windowStartMs)
    assertEquals(2900L, window.windowEndMs)
    assertEquals(3000L, window.windowDurationMs)
    assertTrue(abs(window.perclos - (4.0 / 30.0)) < 1e-6)
    assertTrue(abs(window.blinkRate - 40.0) < 1e-6)
    assertEquals(2, window.yawnCount)
    assertEquals(HeadPose.LEFT, window.headPose)
  }

  @Test
  fun `update keeps only configured window range`() {
    val engine = TemporalEngine(config = TemporalEngineConfig(windowSizeMs = 3_000L))

    engine.update(listOf(detection("eye_open", 0L)))
    engine.update(listOf(detection("eye_open", 1_000L)))
    engine.update(listOf(detection("eye_open", 2_000L)))
    val window = engine.update(listOf(detection("eye_closed", 4_000L)))

    requireNotNull(window)
    assertEquals(1_000L, window.windowStartMs)
    assertEquals(4_000L, window.windowEndMs)
    assertTrue(abs(window.perclos - (1.0 / 3.0)) < 1e-6)
  }

  @Test
  fun `update returns null before first detection`() {
    val engine = TemporalEngine()
    assertNull(engine.update(emptyList()))
  }

  private fun detection(
    label: String,
    timestampMs: Long,
    confidence: Float = 0.9f,
  ): DetectionResult =
    DetectionResult(
      classId = 0,
      label = label,
      confidence = confidence,
      box = BoundingBox(
        left = 0f,
        top = 0f,
        right = 1f,
        bottom = 1f,
      ),
      frameTimestampMs = timestampMs,
    )
}
