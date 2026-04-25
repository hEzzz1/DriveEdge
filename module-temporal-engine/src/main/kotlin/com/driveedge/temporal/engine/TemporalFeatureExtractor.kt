package com.driveedge.temporal.engine

import com.driveedge.infer.yolo.DetectionResult
import kotlin.math.max

object TemporalFeatureExtractor {
  fun extract(
    detections: List<DetectionResult>,
    config: TemporalEngineConfig = TemporalEngineConfig(),
  ): FeatureWindow {
    if (detections.isEmpty()) {
      return FeatureWindow(
        windowStartMs = 0L,
        windowEndMs = 0L,
        windowDurationMs = 0L,
        perclos = 0.0,
        blinkRate = 0.0,
        yawnCount = 0,
        headPose = HeadPose.UNKNOWN,
        headPitch = 0.0,
        headYaw = 0.0,
        gazeOffset = 0.0,
        headPoseStability = 0.0,
        headDownDurationMs = 0L,
        gazeOffsetDurationMs = 0L,
      )
    }

    val frames = buildFrameSeries(detections, config)
    val windowStart = frames.first().timestampMs
    val windowEnd = frames.last().timestampMs
    val frameIntervalMs = estimateFrameIntervalMs(frames)
    val windowDurationMs = max(1L, (windowEnd - windowStart) + frameIntervalMs)

    val perclos = computePerclos(frames)
    val blinkCount = computeBlinkCount(frames, frameIntervalMs, config)
    val blinkRate = blinkCount * 60_000.0 / windowDurationMs.toDouble()
    val yawnCount = computeYawnCount(frames, frameIntervalMs, config)
    val headPose = computeDominantHeadPose(frames)
    val headPitch = averageOf(frames) { it.headPitch }
    val headYaw = averageOf(frames) { it.headYaw }
    val gazeOffset = averageOf(frames) { it.gazeOffset }
    val headPoseStability = computeHeadPoseStability(frames)
    val headDownDurationMs = computeSustainedDurationMs(frames, frameIntervalMs) {
      it.headPitch >= config.headDownThreshold
    }
    val gazeOffsetDurationMs = computeSustainedDurationMs(frames, frameIntervalMs) {
      it.gazeOffset >= config.gazeOffsetThreshold
    }

    return FeatureWindow(
      windowStartMs = windowStart,
      windowEndMs = windowEnd,
      windowDurationMs = windowDurationMs,
      perclos = perclos,
      blinkRate = blinkRate,
      yawnCount = yawnCount,
      headPose = headPose,
      headPitch = headPitch,
      headYaw = headYaw,
      gazeOffset = gazeOffset,
      headPoseStability = headPoseStability,
      headDownDurationMs = headDownDurationMs,
      gazeOffsetDurationMs = gazeOffsetDurationMs,
    )
  }

  fun smooth(
    windows: List<FeatureWindow>,
    previousWindow: FeatureWindow?,
    config: TemporalEngineConfig = TemporalEngineConfig(),
  ): FeatureWindow {
    if (windows.isEmpty()) {
      return previousWindow ?: extract(emptyList(), config)
    }
    val latest = windows.last()
    val previous = previousWindow ?: return latest
    val alpha = config.featureEmaAlpha

    return FeatureWindow(
      windowStartMs = latest.windowStartMs,
      windowEndMs = latest.windowEndMs,
      windowDurationMs = latest.windowDurationMs,
      perclos = ema(previous.perclos, latest.perclos, alpha),
      blinkRate = ema(previous.blinkRate, latest.blinkRate, alpha),
      yawnCount = ema(previous.yawnCount.toDouble(), latest.yawnCount.toDouble(), alpha).toInt(),
      headPose = smoothHeadPose(windows, previous.headPose, config),
      headPitch = ema(previous.headPitch, latest.headPitch, alpha),
      headYaw = ema(previous.headYaw, latest.headYaw, alpha),
      gazeOffset = ema(previous.gazeOffset, latest.gazeOffset, alpha),
      headPoseStability = ema(previous.headPoseStability, latest.headPoseStability, alpha),
      headDownDurationMs = smoothDurationMs(
        windows = windows,
        previousDurationMs = previous.headDownDurationMs,
        latestDurationMs = latest.headDownDurationMs,
        triggerThresholdMs = config.headDownMinDurationMs,
        selector = { it.headDownDurationMs },
        config = config,
      ),
      gazeOffsetDurationMs = smoothDurationMs(
        windows = windows,
        previousDurationMs = previous.gazeOffsetDurationMs,
        latestDurationMs = latest.gazeOffsetDurationMs,
        triggerThresholdMs = config.gazeOffsetMinDurationMs,
        selector = { it.gazeOffsetDurationMs },
        config = config,
      ),
    )
  }

  private fun buildFrameSeries(
    detections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): List<FrameState> =
    detections
      .groupBy { it.frameTimestampMs }
      .map { (timestampMs, frameDetections) ->
        FrameState(
          timestampMs = timestampMs,
          eyeState = pickEyeState(frameDetections, config),
          yawn = hasYawn(frameDetections, config),
          pose = pickHeadPose(frameDetections, config),
          headPitch = pickHeadPitch(frameDetections, config),
          headYaw = pickHeadYaw(frameDetections, config),
          gazeOffset = pickGazeOffset(frameDetections, config),
        )
      }
      .sortedBy { it.timestampMs }

  private fun pickEyeState(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): EyeState {
    val closedScore = frameDetections.maxScore(config.eyeClosedLabels, config.minSignalConfidence)
    val openScore = frameDetections.maxScore(config.eyeOpenLabels, config.minSignalConfidence)
    if (closedScore == null && openScore == null) {
      return EyeState.UNKNOWN
    }
    return if ((closedScore ?: -1f) >= (openScore ?: -1f)) EyeState.CLOSED else EyeState.OPEN
  }

  private fun hasYawn(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): Boolean = frameDetections.maxScore(config.yawnLabels, config.minSignalConfidence) != null

  private fun pickHeadPose(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): HeadPose {
    val poseScores = mapOf(
      HeadPose.DOWN to frameDetections.maxScore(config.headDownLabels, config.minSignalConfidence),
      HeadPose.LEFT to frameDetections.maxScore(config.headLeftLabels, config.minSignalConfidence),
      HeadPose.RIGHT to frameDetections.maxScore(config.headRightLabels, config.minSignalConfidence),
      HeadPose.FORWARD to frameDetections.maxScore(config.headForwardLabels, config.minSignalConfidence),
    )

    val best = poseScores
      .filterValues { it != null }
      .maxByOrNull { it.value ?: -1f }

    return best?.key ?: HeadPose.UNKNOWN
  }

  private fun pickHeadPitch(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): Double = (frameDetections.maxScore(config.headDownLabels, config.minSignalConfidence) ?: 0f).toDouble()

  private fun pickHeadYaw(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): Double {
    val left = frameDetections.maxScore(config.headLeftLabels, config.minSignalConfidence) ?: 0f
    val right = frameDetections.maxScore(config.headRightLabels, config.minSignalConfidence) ?: 0f
    return (right - left).toDouble()
  }

  private fun pickGazeOffset(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): Double {
    val left = frameDetections.maxScore(config.gazeLeftLabels, config.minSignalConfidence) ?: 0f
    val right = frameDetections.maxScore(config.gazeRightLabels, config.minSignalConfidence) ?: 0f
    val down = frameDetections.maxScore(config.gazeDownLabels, config.minSignalConfidence) ?: 0f
    val forward = frameDetections.maxScore(config.gazeForwardLabels, config.minSignalConfidence) ?: 0f
    return maxOf(left, right, down).minus(forward).coerceAtLeast(0f).toDouble()
  }

  private fun computePerclos(frames: List<FrameState>): Double {
    val validCount = frames.count { it.eyeState != EyeState.UNKNOWN }
    if (validCount == 0) {
      return 0.0
    }
    val closedCount = frames.count { it.eyeState == EyeState.CLOSED }
    return closedCount.toDouble() / validCount.toDouble()
  }

  private fun computeBlinkCount(
    frames: List<FrameState>,
    frameIntervalMs: Long,
    config: TemporalEngineConfig,
  ): Int {
    val runs = buildEyeRuns(frames)
    if (runs.size < 3) {
      return 0
    }

    var blinkCount = 0
    for (idx in 1 until runs.lastIndex) {
      val prev = runs[idx - 1]
      val cur = runs[idx]
      val next = runs[idx + 1]
      if (prev.state != EyeState.OPEN || cur.state != EyeState.CLOSED || next.state != EyeState.OPEN) {
        continue
      }
      val durationMs = runDurationMs(cur, frameIntervalMs)
      if (durationMs in config.blinkMinDurationMs..config.blinkMaxDurationMs) {
        blinkCount += 1
      }
    }
    return blinkCount
  }

  private fun computeYawnCount(
    frames: List<FrameState>,
    frameIntervalMs: Long,
    config: TemporalEngineConfig,
  ): Int {
    var count = 0
    var runStart: Long? = null
    var runEnd: Long? = null
    for (frame in frames) {
      if (frame.yawn) {
        if (runStart == null) {
          runStart = frame.timestampMs
        }
        runEnd = frame.timestampMs
        continue
      }
      if (runStart != null && runEnd != null) {
        val durationMs = (runEnd - runStart) + frameIntervalMs
        if (durationMs >= config.yawnMinDurationMs) {
          count += 1
        }
      }
      runStart = null
      runEnd = null
    }
    if (runStart != null && runEnd != null) {
      val durationMs = (runEnd - runStart) + frameIntervalMs
      if (durationMs >= config.yawnMinDurationMs) {
        count += 1
      }
    }
    return count
  }

  private fun computeDominantHeadPose(frames: List<FrameState>): HeadPose {
    val counts = frames
      .map { it.pose }
      .filter { it != HeadPose.UNKNOWN }
      .groupingBy { it }
      .eachCount()

    return counts.maxByOrNull { it.value }?.key ?: HeadPose.UNKNOWN
  }

  private fun computeHeadPoseStability(frames: List<FrameState>): Double {
    val validPoses = frames.map { it.pose }.filter { it != HeadPose.UNKNOWN }
    if (validPoses.isEmpty()) {
      return 0.0
    }
    val dominantCount = validPoses.groupingBy { it }.eachCount().maxOf { it.value }
    return dominantCount.toDouble() / validPoses.size.toDouble()
  }

  private fun computeSustainedDurationMs(
    frames: List<FrameState>,
    frameIntervalMs: Long,
    predicate: (FrameState) -> Boolean,
  ): Long {
    var best = 0L
    var runStart: Long? = null
    var runEnd: Long? = null
    for (frame in frames) {
      if (predicate(frame)) {
        if (runStart == null) {
          runStart = frame.timestampMs
        }
        runEnd = frame.timestampMs
        continue
      }
      if (runStart != null && runEnd != null) {
        best = max(best, (runEnd - runStart) + frameIntervalMs)
      }
      runStart = null
      runEnd = null
    }
    if (runStart != null && runEnd != null) {
      best = max(best, (runEnd - runStart) + frameIntervalMs)
    }
    return best
  }

  private fun averageOf(
    frames: List<FrameState>,
    selector: (FrameState) -> Double,
  ): Double {
    if (frames.isEmpty()) {
      return 0.0
    }
    return frames.sumOf(selector) / frames.size.toDouble()
  }

  private fun smoothHeadPose(
    windows: List<FeatureWindow>,
    previousPose: HeadPose,
    config: TemporalEngineConfig,
  ): HeadPose {
    val poses = windows.map { it.headPose }.filter { it != HeadPose.UNKNOWN }
    if (poses.isEmpty()) {
      return previousPose
    }

    val counts = poses.groupingBy { it }.eachCount()
    val dominant = counts.maxByOrNull { it.value }?.key ?: HeadPose.UNKNOWN
    val dominantCount = counts[dominant] ?: 0
    if (dominantCount >= config.stableWindowHitCount) {
      return dominant
    }

    if (previousPose != HeadPose.UNKNOWN) {
      val recent = poses.takeLast(minOf(config.clearWindowCount, poses.size))
      val clearHits = recent.count { it != previousPose }
      if (clearHits < config.clearWindowCount) {
        return previousPose
      }
    }

    return dominant
  }

  private fun smoothDurationMs(
    windows: List<FeatureWindow>,
    previousDurationMs: Long,
    latestDurationMs: Long,
    triggerThresholdMs: Long,
    selector: (FeatureWindow) -> Long,
    config: TemporalEngineConfig,
  ): Long {
    val hitCount = windows.count { selector(it) >= triggerThresholdMs }
    val recentClearCount = windows.takeLast(minOf(config.clearWindowCount, windows.size)).count {
      selector(it) < triggerThresholdMs
    }
    return when {
      hitCount >= config.stableWindowHitCount -> max(previousDurationMs, latestDurationMs)
      previousDurationMs >= triggerThresholdMs && recentClearCount < config.clearWindowCount ->
        max((previousDurationMs * (1.0 - (1.0 - config.featureEmaAlpha) * 0.5)).toLong(), latestDurationMs)
      else -> ema(previousDurationMs.toDouble(), latestDurationMs.toDouble(), config.featureEmaAlpha).toLong()
    }
  }

  private fun ema(
    previousValue: Double,
    latestValue: Double,
    alpha: Double,
  ): Double = (alpha * latestValue) + ((1.0 - alpha) * previousValue)

  private fun buildEyeRuns(frames: List<FrameState>): List<EyeRun> {
    if (frames.isEmpty()) {
      return emptyList()
    }

    val runs = mutableListOf<EyeRun>()
    var state = frames.first().eyeState
    var startMs = frames.first().timestampMs
    var endMs = startMs

    for (idx in 1 until frames.size) {
      val frame = frames[idx]
      if (frame.eyeState == state) {
        endMs = frame.timestampMs
        continue
      }
      runs += EyeRun(state = state, startMs = startMs, endMs = endMs)
      state = frame.eyeState
      startMs = frame.timestampMs
      endMs = frame.timestampMs
    }

    runs += EyeRun(state = state, startMs = startMs, endMs = endMs)
    return runs
  }

  private fun runDurationMs(run: EyeRun, frameIntervalMs: Long): Long =
    (run.endMs - run.startMs) + frameIntervalMs

  private fun estimateFrameIntervalMs(frames: List<FrameState>): Long {
    if (frames.size < 2) {
      return 100L
    }
    val deltas = frames
      .zipWithNext { a, b -> b.timestampMs - a.timestampMs }
      .filter { it > 0L }
      .sorted()

    if (deltas.isEmpty()) {
      return 100L
    }
    return deltas[deltas.size / 2]
  }

  private fun List<DetectionResult>.maxScore(
    labelSet: Set<String>,
    minConfidence: Float,
  ): Float? {
    if (labelSet.isEmpty()) {
      return null
    }
    val normalized = labelSet.asSequence().map { it.lowercase() }.toSet()
    return this
      .asSequence()
      .filter { it.label.lowercase() in normalized && it.confidence >= minConfidence }
      .map { it.confidence }
      .maxOrNull()
  }

  private data class FrameState(
    val timestampMs: Long,
    val eyeState: EyeState,
    val yawn: Boolean,
    val pose: HeadPose,
    val headPitch: Double,
    val headYaw: Double,
    val gazeOffset: Double,
  )

  private data class EyeRun(
    val state: EyeState,
    val startMs: Long,
    val endMs: Long,
  )

  private enum class EyeState {
    OPEN,
    CLOSED,
    UNKNOWN,
  }
}
