package com.driveedge.app.evidence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class EvidenceFrameArchiveWriterTest {
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void writeArchiveCreatesStoredZipWithManifestAndFrames() throws Exception {
    EvidenceFrameArchiveWriter writer = new EvidenceFrameArchiveWriter();
    byte[] firstFrame = "first-jpeg".getBytes(StandardCharsets.UTF_8);
    byte[] secondFrame = "second-jpeg".getBytes(StandardCharsets.UTF_8);

    File archive = writer.writeArchive(
      temporaryFolder.getRoot(),
      "alert-sequence",
      Arrays.asList(
        new EvidenceFrameBuffer.EvidenceFrame(1_000L, firstFrame, 320, 180),
        new EvidenceFrameBuffer.EvidenceFrame(1_500L, secondFrame, 320, 180)
      ),
      1_500L,
      1024 * 1024
    );

    assertNotNull(archive);
    try (ZipFile zipFile = new ZipFile(archive)) {
      ZipEntry manifest = zipFile.getEntry("manifest.json");
      ZipEntry first = zipFile.getEntry("frames/001_1000_320x180.jpg");
      ZipEntry second = zipFile.getEntry("frames/002_1500_320x180.jpg");

      assertNotNull(manifest);
      assertNotNull(first);
      assertNotNull(second);
      assertEquals(ZipEntry.STORED, first.getMethod());
      assertArrayEquals(firstFrame, zipFile.getInputStream(first).readAllBytes());
      assertArrayEquals(secondFrame, zipFile.getInputStream(second).readAllBytes());
    }
  }
}
