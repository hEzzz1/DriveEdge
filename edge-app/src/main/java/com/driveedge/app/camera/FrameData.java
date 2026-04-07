package com.driveedge.app.camera;

public final class FrameData {
  public final int width;
  public final int height;
  public final int rotationDegrees;
  public final long timestampNs;
  public final byte[] nv21;

  public FrameData(int width, int height, int rotationDegrees, long timestampNs, byte[] nv21) {
    this.width = width;
    this.height = height;
    this.rotationDegrees = rotationDegrees;
    this.timestampNs = timestampNs;
    this.nv21 = nv21;
  }
}
