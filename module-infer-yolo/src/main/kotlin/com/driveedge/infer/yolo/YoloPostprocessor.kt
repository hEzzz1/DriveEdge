package com.driveedge.infer.yolo

class YoloPostprocessor {
  fun process(
    rawDetections: List<RawDetection>,
    frame: PreprocessedFrame,
    config: YoloConfig,
  ): List<DetectionResult> {
    if (rawDetections.isEmpty()) {
      return emptyList()
    }

    val candidates = rawDetections.asSequence()
      .filter { it.confidence >= config.confidenceThreshold }
      .mapNotNull { toDetectionCandidate(it, frame, config) }
      .toList()

    if (candidates.isEmpty()) {
      return emptyList()
    }

    val nmsKept = if (config.classAgnosticNms) {
      nms(candidates, config.iouThreshold)
    } else {
      candidates
        .groupBy { it.result.classId }
        .values
        .flatMap { nms(it, config.iouThreshold) }
    }

    return nmsKept
      .sortedByDescending { it.result.confidence }
      .take(config.maxDetections)
      .map { it.result }
  }

  private fun toDetectionCandidate(
    raw: RawDetection,
    frame: PreprocessedFrame,
    config: YoloConfig,
  ): DetectionCandidate? {
    val x1 = ((raw.left - frame.padX.toFloat()) / frame.scale).coerceIn(0f, frame.originalWidth.toFloat())
    val y1 = ((raw.top - frame.padY.toFloat()) / frame.scale).coerceIn(0f, frame.originalHeight.toFloat())
    val x2 = ((raw.right - frame.padX.toFloat()) / frame.scale).coerceIn(0f, frame.originalWidth.toFloat())
    val y2 = ((raw.bottom - frame.padY.toFloat()) / frame.scale).coerceIn(0f, frame.originalHeight.toFloat())

    if (x2 <= x1 || y2 <= y1) {
      return null
    }

    val label = config.labels.getOrNull(raw.classId) ?: "class_${raw.classId}"
    val result = DetectionResult(
      classId = raw.classId,
      label = label,
      confidence = raw.confidence,
      box = BoundingBox(
        left = x1,
        top = y1,
        right = x2,
        bottom = y2,
      ),
      frameTimestampMs = frame.timestampMs,
    )
    return DetectionCandidate(result)
  }

  private fun nms(
    candidates: List<DetectionCandidate>,
    iouThreshold: Float,
  ): List<DetectionCandidate> {
    if (candidates.size <= 1) {
      return candidates
    }

    val sorted = candidates.sortedByDescending { it.result.confidence }
    val removed = BooleanArray(sorted.size)
    val kept = mutableListOf<DetectionCandidate>()

    for (i in sorted.indices) {
      if (removed[i]) {
        continue
      }
      val current = sorted[i]
      kept += current
      for (j in (i + 1) until sorted.size) {
        if (removed[j]) {
          continue
        }
        val iou = iou(current.result.box, sorted[j].result.box)
        if (iou > iouThreshold) {
          removed[j] = true
        }
      }
    }

    return kept
  }

  private fun iou(a: BoundingBox, b: BoundingBox): Float {
    val interLeft = maxOf(a.left, b.left)
    val interTop = maxOf(a.top, b.top)
    val interRight = minOf(a.right, b.right)
    val interBottom = minOf(a.bottom, b.bottom)

    if (interRight <= interLeft || interBottom <= interTop) {
      return 0f
    }

    val interArea = (interRight - interLeft) * (interBottom - interTop)
    val union = a.area() + b.area() - interArea
    if (union <= 0f) {
      return 0f
    }
    return interArea / union
  }
}

private data class DetectionCandidate(
  val result: DetectionResult,
)
