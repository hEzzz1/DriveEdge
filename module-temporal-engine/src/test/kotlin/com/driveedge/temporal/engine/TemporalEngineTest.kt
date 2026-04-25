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
      if (idx in 6..22) {
        detections += detection("look_down", ts, confidence = 0.70f)
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
    assertTrue(abs(window.headPitch - (9 * 0.75 / 30.0)) < 1e-6)
    assertTrue(abs(window.headYaw - (-21 * 0.80 / 30.0)) < 1e-6)
    assertTrue(abs(window.gazeOffset - (17 * 0.70 / 30.0)) < 1e-6)
    assertTrue(abs(window.headPoseStability - (21.0 / 30.0)) < 1e-6)
    assertEquals(900L, window.headDownDurationMs)
    assertEquals(1700L, window.gazeOffsetDurationMs)
  }

  @Test
  fun `update keeps only configured window range`() {
    val engine =
      TemporalEngine(
        config =
          TemporalEngineConfig(
            windowSizeMs = 3_000L,
            smoothingWindowCount = 1,
            stableWindowHitCount = 1,
            clearWindowCount = 1,
            featureEmaAlpha = 1.0,
          ),
      )

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

  @Test
  fun `extract ignores weak confidence noise when computing distraction features`() {
    val detections = buildList {
      for (idx in 0 until 20) {
        val ts = idx * 100L
        add(detection("eye_open", ts, confidence = 0.90f))
        add(detection("head_forward", ts, confidence = 0.85f))
        if (idx in 5..16) {
          add(detection("head_down", ts, confidence = 0.92f))
          add(detection("look_down", ts, confidence = 0.88f))
        } else {
          add(detection("look_forward", ts, confidence = 0.80f))
        }
        add(detection("head_left", ts, confidence = 0.20f))
        add(detection("look_left", ts, confidence = 0.18f))
      }
    }

    val window = TemporalFeatureExtractor.extract(detections)

    assertEquals(HeadPose.DOWN, window.headPose)
    assertTrue(window.headPitch > 0.5)
    assertTrue(window.gazeOffset > 0.0)
    assertEquals(1_200L, window.headDownDurationMs)
    assertEquals(1_200L, window.gazeOffsetDurationMs)
    assertTrue(abs(window.headYaw) < 1e-6)
  }

  @Test
  fun `update smooths head pose transition across consecutive windows`() {
    val engine =
      TemporalEngine(
        config =
          TemporalEngineConfig(
            windowSizeMs = 3_000L,
            smoothingWindowCount = 3,
            stableWindowHitCount = 2,
            clearWindowCount = 2,
            featureEmaAlpha = 0.5,
          ),
      )

    val first = engine.update(listOf(detection("head_left", 0L, confidence = 0.9f)))
    val second = engine.update(listOf(detection("head_left", 1_000L, confidence = 0.9f)))
    val third = engine.update(listOf(detection("head_forward", 4_000L, confidence = 0.9f)))
    val fourth = engine.update(listOf(detection("head_forward", 5_000L, confidence = 0.9f)))
    val fifth = engine.update(listOf(detection("head_forward", 6_000L, confidence = 0.9f)))

    requireNotNull(first)
    requireNotNull(second)
    requireNotNull(third)
    requireNotNull(fourth)
    requireNotNull(fifth)
    assertEquals(HeadPose.LEFT, first.headPose)
    assertEquals(HeadPose.LEFT, second.headPose)
    assertEquals(HeadPose.LEFT, third.headPose)
    assertEquals(HeadPose.LEFT, fourth.headPose)
    assertEquals(HeadPose.FORWARD, fifth.headPose)
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
