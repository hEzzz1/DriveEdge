package com.driveedge.infer.yolo

enum class PixelFormat {
  RGB24,
}

data class FramePacket(
  val width: Int,
  val height: Int,
  val data: ByteArray,
  val timestampMs: Long,
  val pixelFormat: PixelFormat = PixelFormat.RGB24,
) {
  init {
    require(width > 0) { "width must be > 0" }
    require(height > 0) { "height must be > 0" }
    require(data.size == width * height * 3) {
      "For RGB24, data size must be width*height*3, but was ${data.size}"
    }
  }
}
