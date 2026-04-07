package com.driveedge.infer.yolo

import kotlin.math.min
import kotlin.math.roundToInt

class YoloPreprocessor {
  fun preprocess(frame: FramePacket, config: YoloConfig): PreprocessedFrame {
    require(frame.pixelFormat == PixelFormat.RGB24) {
      "Only RGB24 is supported, but got ${frame.pixelFormat}"
    }

    val inputWidth = config.inputWidth
    val inputHeight = config.inputHeight

    val scale = min(
      inputWidth.toFloat() / frame.width.toFloat(),
      inputHeight.toFloat() / frame.height.toFloat(),
    )

    val resizedWidth = maxOf(1, (frame.width * scale).roundToInt())
    val resizedHeight = maxOf(1, (frame.height * scale).roundToInt())
    val padX = (inputWidth - resizedWidth) / 2
    val padY = (inputHeight - resizedHeight) / 2

    val planeSize = inputWidth * inputHeight
    val tensor = FloatArray(planeSize * 3)

    for (y in 0 until resizedHeight) {
      val srcY = min(frame.height - 1, (y / scale).toInt())
      for (x in 0 until resizedWidth) {
        val srcX = min(frame.width - 1, (x / scale).toInt())
        val srcIndex = (srcY * frame.width + srcX) * 3
        val dstX = x + padX
        val dstY = y + padY
        val dstIndex = dstY * inputWidth + dstX

        val r = (frame.data[srcIndex].toInt() and 0xFF) * config.normalizeScale
        val g = (frame.data[srcIndex + 1].toInt() and 0xFF) * config.normalizeScale
        val b = (frame.data[srcIndex + 2].toInt() and 0xFF) * config.normalizeScale

        tensor[dstIndex] = r
        tensor[planeSize + dstIndex] = g
        tensor[(planeSize * 2) + dstIndex] = b
      }
    }

    return PreprocessedFrame(
      tensorChw = tensor,
      modelInputWidth = inputWidth,
      modelInputHeight = inputHeight,
      scale = scale,
      padX = padX,
      padY = padY,
      originalWidth = frame.width,
      originalHeight = frame.height,
      timestampMs = frame.timestampMs,
    )
  }
}
