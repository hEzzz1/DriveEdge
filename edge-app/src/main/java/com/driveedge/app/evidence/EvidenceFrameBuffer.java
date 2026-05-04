package com.driveedge.app.evidence;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class EvidenceFrameBuffer {
  @NonNull
  private final Deque<EvidenceFrame> frames = new ArrayDeque<>();
  private long lastAcceptedAtMs = Long.MIN_VALUE;

  public synchronized boolean offer(
    long capturedAtMs,
    @NonNull byte[] jpegBytes,
    int width,
    int height,
    long retentionWindowMs,
    long sampleIntervalMs,
    int maxFrames
  ) {
    pruneLocked(capturedAtMs, retentionWindowMs, maxFrames);
    if (jpegBytes.length == 0) {
      return false;
    }
    if (lastAcceptedAtMs != Long.MIN_VALUE && capturedAtMs - lastAcceptedAtMs < sampleIntervalMs) {
      return false;
    }

    frames.addLast(new EvidenceFrame(capturedAtMs, jpegBytes, width, height));
    lastAcceptedAtMs = capturedAtMs;
    pruneLocked(capturedAtMs, retentionWindowMs, maxFrames);
    return true;
  }

  @NonNull
  public synchronized List<EvidenceFrame> snapshot(long eventCapturedAtMs, long windowMs, int maxFrames) {
    pruneLocked(eventCapturedAtMs, windowMs, maxFrames);
    long windowStartMs = eventCapturedAtMs - Math.max(0L, windowMs);
    return snapshotRangeLocked(windowStartMs, eventCapturedAtMs, maxFrames);
  }

  @NonNull
  public synchronized List<EvidenceFrame> snapshotRange(long windowStartMs, long windowEndMs, long retentionWindowMs, int maxFrames) {
    pruneLocked(windowEndMs, retentionWindowMs, maxFrames);
    return snapshotRangeLocked(windowStartMs, windowEndMs, maxFrames);
  }

  @NonNull
  private List<EvidenceFrame> snapshotRangeLocked(long windowStartMs, long windowEndMs, int maxFrames) {
    ArrayList<EvidenceFrame> selected = new ArrayList<>();
    for (EvidenceFrame frame : frames) {
      if (frame.capturedAtMs >= windowStartMs && frame.capturedAtMs <= windowEndMs) {
        selected.add(frame);
      }
    }
    int boundedMaxFrames = Math.max(1, maxFrames);
    if (selected.size() <= boundedMaxFrames) {
      return selected;
    }
    return new ArrayList<>(selected.subList(selected.size() - boundedMaxFrames, selected.size()));
  }

  public synchronized void clear() {
    frames.clear();
    lastAcceptedAtMs = Long.MIN_VALUE;
  }

  private void pruneLocked(long nowMs, long retentionWindowMs, int maxFrames) {
    long cutoffMs = nowMs - Math.max(0L, retentionWindowMs);
    while (!frames.isEmpty() && frames.peekFirst().capturedAtMs < cutoffMs) {
      frames.removeFirst();
    }
    int boundedMaxFrames = Math.max(1, maxFrames);
    while (frames.size() > boundedMaxFrames) {
      frames.removeFirst();
    }
  }

  public static final class EvidenceFrame {
    private final long capturedAtMs;
    @NonNull
    private final byte[] jpegBytes;
    private final int width;
    private final int height;

    public EvidenceFrame(long capturedAtMs, @NonNull byte[] jpegBytes, int width, int height) {
      this.capturedAtMs = capturedAtMs;
      this.jpegBytes = Arrays.copyOf(jpegBytes, jpegBytes.length);
      this.width = width;
      this.height = height;
    }

    public long capturedAtMs() {
      return capturedAtMs;
    }

    @NonNull
    public byte[] jpegBytes() {
      return jpegBytes;
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }
  }
}
