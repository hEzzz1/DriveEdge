package com.driveedge.app.camera;

public final class FrameData {
  public final int width;
  public final int height;
  public final int rotationDegrees;
  public final long timestampNs;
  public final byte[] rgba8888;

  public FrameData(int width, int height, int rotationDegrees, long timestampNs, byte[] rgba8888) {
    this.width = width;
    this.height = height;
    this.rotationDegrees = rotationDegrees;
    this.timestampNs = timestampNs;
    this.rgba8888 = rgba8888;
  }
}
