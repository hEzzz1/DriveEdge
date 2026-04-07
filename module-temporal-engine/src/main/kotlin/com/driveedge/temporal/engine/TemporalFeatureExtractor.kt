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

    return FeatureWindow(
      windowStartMs = windowStart,
      windowEndMs = windowEnd,
      windowDurationMs = windowDurationMs,
      perclos = perclos,
      blinkRate = blinkRate,
      yawnCount = yawnCount,
      headPose = headPose,
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
        )
      }
      .sortedBy { it.timestampMs }

  private fun pickEyeState(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): EyeState {
    val closedScore = frameDetections.maxScore(config.eyeClosedLabels)
    val openScore = frameDetections.maxScore(config.eyeOpenLabels)
    if (closedScore == null && openScore == null) {
      return EyeState.UNKNOWN
    }
    return if ((closedScore ?: -1f) >= (openScore ?: -1f)) EyeState.CLOSED else EyeState.OPEN
  }

  private fun hasYawn(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): Boolean = frameDetections.maxScore(config.yawnLabels) != null

  private fun pickHeadPose(
    frameDetections: List<DetectionResult>,
    config: TemporalEngineConfig,
  ): HeadPose {
    val poseScores = mapOf(
      HeadPose.DOWN to frameDetections.maxScore(config.headDownLabels),
      HeadPose.LEFT to frameDetections.maxScore(config.headLeftLabels),
      HeadPose.RIGHT to frameDetections.maxScore(config.headRightLabels),
      HeadPose.FORWARD to frameDetections.maxScore(config.headForwardLabels),
    )

    val best = poseScores
      .filterValues { it != null }
      .maxByOrNull { it.value ?: -1f }

    return best?.key ?: HeadPose.UNKNOWN
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

  private fun List<DetectionResult>.maxScore(labelSet: Set<String>): Float? {
    if (labelSet.isEmpty()) {
      return null
    }
    val normalized = labelSet.asSequence().map { it.lowercase() }.toSet()
    return this
      .asSequence()
      .filter { it.label.lowercase() in normalized }
      .map { it.confidence }
      .maxOrNull()
  }

  private data class FrameState(
    val timestampMs: Long,
    val eyeState: EyeState,
    val yawn: Boolean,
    val pose: HeadPose,
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
