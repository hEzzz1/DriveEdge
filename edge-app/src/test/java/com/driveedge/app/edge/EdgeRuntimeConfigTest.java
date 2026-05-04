package com.driveedge.app.edge;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class EdgeRuntimeConfigTest {
  @Test
  public void defaultsPreferVideoClipEvidencePolicy() {
    EdgeRuntimeConfig config = EdgeRuntimeConfig.defaults();

    assertEquals("VIDEO_CLIP", config.evidenceType());
    assertEquals("video/mp4", config.evidenceMimeType());
    assertEquals(8 * 1024 * 1024, config.evidenceMaxBytes());
    assertEquals(8_000L, config.evidenceSequenceWindowMs());
    assertEquals(3_000L, config.evidencePostWindowMs());
    assertEquals(24, config.evidenceSequenceMaxFrames());
  }

  @Test
  public void fromJsonObjectParsesFrameSequenceEvidencePolicy() throws Exception {
    EdgeRuntimeConfig config = EdgeRuntimeConfig.fromJsonObject(new JSONObject(
      "{"
        + "\"evidencePolicy\":{"
        + "\"enabled\":true,"
        + "\"type\":\"FRAME_SEQUENCE\","
        + "\"mimeType\":\"application/zip\","
        + "\"jpegQuality\":70,"
        + "\"maxBytes\":8388608,"
        + "\"sequenceWindowMs\":12000,"
        + "\"postWindowMs\":4000,"
        + "\"sequenceSampleIntervalMs\":600,"
        + "\"sequenceMaxFrames\":20"
        + "}"
        + "}"
    ));

    assertEquals("FRAME_SEQUENCE", config.evidenceType());
    assertEquals("application/zip", config.evidenceMimeType());
    assertEquals(70, config.evidenceJpegQuality());
    assertEquals(8 * 1024 * 1024, config.evidenceMaxBytes());
    assertEquals(12_000L, config.evidenceSequenceWindowMs());
    assertEquals(4_000L, config.evidencePostWindowMs());
    assertEquals(600L, config.evidenceSequenceSampleIntervalMs());
    assertEquals(20, config.evidenceSequenceMaxFrames());
  }
}
