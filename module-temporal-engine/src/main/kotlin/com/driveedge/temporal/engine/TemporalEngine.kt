package com.driveedge.temporal.engine

import com.driveedge.infer.yolo.DetectionResult

class TemporalEngine(
  private val config: TemporalEngineConfig = TemporalEngineConfig(),
) {
  private val buffer: ArrayDeque<DetectionResult> = ArrayDeque()

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

    return TemporalFeatureExtractor.extract(buffer.toList(), config)
  }

  fun reset() {
    buffer.clear()
  }
}
