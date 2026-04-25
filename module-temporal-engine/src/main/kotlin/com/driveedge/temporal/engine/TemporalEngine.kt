package com.driveedge.temporal.engine

import com.driveedge.infer.yolo.DetectionResult
import kotlin.jvm.JvmOverloads

class TemporalEngine @JvmOverloads constructor(
  private val config: TemporalEngineConfig = TemporalEngineConfig(),
) {
  private val buffer: ArrayDeque<DetectionResult> = ArrayDeque()
  private val rawWindowHistory: ArrayDeque<FeatureWindow> = ArrayDeque()
  private var smoothedWindow: FeatureWindow? = null

  fun update(detections: List<DetectionResult>): FeatureWindow? {
    if (detections.isNotEmpty()) {
      detections
        .sortedBy { it.frameTimestampMs }
        .forEach { buffer.addLast(it) }
    }
    if (buffer.isEmpty()) {
      return null
    }

    if (buffer.size > 1) {
      val sorted = buffer.sortedBy { it.frameTimestampMs }
      buffer.clear()
      sorted.forEach { buffer.addLast(it) }
    }

    val latestTimestampMs = buffer.maxOf { it.frameTimestampMs }
    val cutoff = latestTimestampMs - config.windowSizeMs
    while (buffer.isNotEmpty() && buffer.first().frameTimestampMs < cutoff) {
      buffer.removeFirst()
    }

    val rawWindow = TemporalFeatureExtractor.extract(buffer.toList(), config)
    rawWindowHistory.addLast(rawWindow)
    while (rawWindowHistory.size > config.smoothingWindowCount) {
      rawWindowHistory.removeFirst()
    }

    val smoothed = TemporalFeatureExtractor.smooth(rawWindowHistory.toList(), smoothedWindow, config)
    smoothedWindow = smoothed
    return smoothed
  }

  fun reset() {
    buffer.clear()
    rawWindowHistory.clear()
    smoothedWindow = null
  }
}
