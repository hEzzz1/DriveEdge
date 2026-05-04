package com.driveedge.app.evidence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class EvidenceFrameBufferTest {
  @Test
  public void offerSamplesFramesAndSnapshotKeepsWindow() {
    EvidenceFrameBuffer buffer = new EvidenceFrameBuffer();

    assertTrue(buffer.offer(1_000L, bytes("a"), 320, 180, 2_000L, 500L, 10));
    assertFalse(buffer.offer(1_200L, bytes("b"), 320, 180, 2_000L, 500L, 10));
    assertTrue(buffer.offer(1_600L, bytes("c"), 320, 180, 2_000L, 500L, 10));
    assertTrue(buffer.offer(2_600L, bytes("d"), 320, 180, 2_000L, 500L, 10));
    assertTrue(buffer.offer(4_000L, bytes("e"), 320, 180, 2_000L, 500L, 10));

    List<EvidenceFrameBuffer.EvidenceFrame> frames = buffer.snapshot(4_000L, 2_000L, 10);

    assertEquals(2, frames.size());
    assertEquals(2_600L, frames.get(0).capturedAtMs());
    assertEquals(4_000L, frames.get(1).capturedAtMs());
  }

  @Test
  public void snapshotBoundsFrameCountToLatestFrames() {
    EvidenceFrameBuffer buffer = new EvidenceFrameBuffer();
    buffer.offer(1_000L, bytes("a"), 320, 180, 10_000L, 1L, 10);
    buffer.offer(2_000L, bytes("b"), 320, 180, 10_000L, 1L, 10);
    buffer.offer(3_000L, bytes("c"), 320, 180, 10_000L, 1L, 10);

    List<EvidenceFrameBuffer.EvidenceFrame> frames = buffer.snapshot(3_000L, 10_000L, 2);

    assertEquals(2, frames.size());
    assertEquals(2_000L, frames.get(0).capturedAtMs());
    assertEquals(3_000L, frames.get(1).capturedAtMs());
  }

  @Test
  public void snapshotRangeIncludesPreAndPostWindowFrames() {
    EvidenceFrameBuffer buffer = new EvidenceFrameBuffer();
    buffer.offer(1_000L, bytes("a"), 320, 180, 5_000L, 1L, 10);
    buffer.offer(2_000L, bytes("b"), 320, 180, 5_000L, 1L, 10);
    buffer.offer(3_000L, bytes("c"), 320, 180, 5_000L, 1L, 10);
    buffer.offer(4_000L, bytes("d"), 320, 180, 5_000L, 1L, 10);

    List<EvidenceFrameBuffer.EvidenceFrame> frames = buffer.snapshotRange(1_500L, 3_500L, 5_000L, 10);

    assertEquals(2, frames.size());
    assertEquals(2_000L, frames.get(0).capturedAtMs());
    assertEquals(3_000L, frames.get(1).capturedAtMs());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
