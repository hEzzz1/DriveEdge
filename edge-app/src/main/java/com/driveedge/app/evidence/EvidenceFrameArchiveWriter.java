package com.driveedge.app.evidence;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class EvidenceFrameArchiveWriter {
  @Nullable
  public File writeArchive(
    @NonNull File outputDir,
    @NonNull String archivePrefix,
    @NonNull List<EvidenceFrameBuffer.EvidenceFrame> frames,
    long eventCapturedAtMs,
    long maxBytes
  ) throws IOException {
    if (frames.isEmpty()) {
      return null;
    }

    File safeOutputDir = ensureDirectory(outputDir);
    List<EvidenceFrameBuffer.EvidenceFrame> workingFrames = new ArrayList<>(frames);
    File outputFile;
    while (!workingFrames.isEmpty()) {
      outputFile = new File(safeOutputDir, buildArchiveName(archivePrefix, eventCapturedAtMs));
      writeZip(outputFile, workingFrames, eventCapturedAtMs);
      if (outputFile.length() <= maxBytes) {
        return outputFile;
      }
      if (!outputFile.delete()) {
        outputFile.deleteOnExit();
      }
      workingFrames.remove(0);
    }
    return null;
  }

  private void writeZip(
    @NonNull File outputFile,
    @NonNull List<EvidenceFrameBuffer.EvidenceFrame> frames,
    long eventCapturedAtMs
  ) throws IOException {
    File parent = outputFile.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Failed to create evidence archive directory");
    }
    if (outputFile.exists() && !outputFile.delete()) {
      throw new IOException("Failed to replace existing archive");
    }

    byte[] manifestBytes = buildManifest(frames, eventCapturedAtMs).getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {
      writeStoredEntry(zipOutputStream, "manifest.json", manifestBytes);
      for (int index = 0; index < frames.size(); index++) {
        EvidenceFrameBuffer.EvidenceFrame frame = frames.get(index);
        String entryName = String.format(Locale.US, "frames/%03d_%d_%dx%d.jpg", index + 1, frame.capturedAtMs(), frame.width(), frame.height());
        writeStoredEntry(zipOutputStream, entryName, frame.jpegBytes());
      }
    }
  }

  private void writeStoredEntry(
    @NonNull ZipOutputStream zipOutputStream,
    @NonNull String entryName,
    @NonNull byte[] bytes
  ) throws IOException {
    CRC32 crc32 = new CRC32();
    crc32.update(bytes);
    ZipEntry entry = new ZipEntry(entryName);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(bytes.length);
    entry.setCompressedSize(bytes.length);
    entry.setCrc(crc32.getValue());
    zipOutputStream.putNextEntry(entry);
    zipOutputStream.write(bytes);
    zipOutputStream.closeEntry();
  }

  @NonNull
  private String buildManifest(
    @NonNull List<EvidenceFrameBuffer.EvidenceFrame> frames,
    long eventCapturedAtMs
  ) {
    StringBuilder builder = new StringBuilder(256 + frames.size() * 128);
    builder.append('{');
    builder.append("\"format\":\"driveedge-frame-sequence-v1\",");
    builder.append("\"eventCapturedAtMs\":").append(eventCapturedAtMs).append(',');
    builder.append("\"frameCount\":").append(frames.size()).append(',');
    builder.append("\"frames\":[");
    for (int index = 0; index < frames.size(); index++) {
      EvidenceFrameBuffer.EvidenceFrame frame = frames.get(index);
      if (index > 0) {
        builder.append(',');
      }
      String entry = String.format(Locale.US, "frames/%03d_%d_%dx%d.jpg", index + 1, frame.capturedAtMs(), frame.width(), frame.height());
      builder.append('{');
      builder.append("\"index\":").append(index + 1).append(',');
      builder.append("\"capturedAtMs\":").append(frame.capturedAtMs()).append(',');
      builder.append("\"width\":").append(frame.width()).append(',');
      builder.append("\"height\":").append(frame.height()).append(',');
      builder.append("\"bytes\":").append(frame.jpegBytes().length).append(',');
      builder.append("\"entry\":\"").append(entry).append('"');
      builder.append('}');
    }
    builder.append(']');
    builder.append('}');
    return builder.toString();
  }

  @NonNull
  private File ensureDirectory(@NonNull File outputDir) throws IOException {
    if (outputDir.exists()) {
      if (!outputDir.isDirectory()) {
        throw new IOException("Evidence archive output path is not a directory");
      }
      return outputDir;
    }
    if (!outputDir.mkdirs()) {
      throw new IOException("Failed to create evidence archive directory");
    }
    return outputDir;
  }

  @NonNull
  private String buildArchiveName(@NonNull String archivePrefix, long eventCapturedAtMs) {
    String sanitized = sanitizePrefix(archivePrefix);
    return String.format(Locale.US, "%s_%d_%s.zip", sanitized, eventCapturedAtMs, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
  }

  @NonNull
  private String sanitizePrefix(@NonNull String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
        builder.append(ch);
      } else {
        builder.append('_');
      }
    }
    if (builder.length() == 0) {
      return "evidence";
    }
    return builder.toString();
  }
}
