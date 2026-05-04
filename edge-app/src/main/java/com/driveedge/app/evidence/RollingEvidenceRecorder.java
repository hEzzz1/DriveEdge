package com.driveedge.app.evidence;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RollingEvidenceRecorder {
  private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
  private static final long DRAIN_TIMEOUT_US = 10_000L;
  private static final long SEGMENT_DURATION_MS = 5_000L;
  private static final long EXTRA_RETENTION_MS = 2_000L;
  private static final long FROZEN_SEGMENT_HOLD_MS = 2 * 60 * 1000L;

  @NonNull
  private final Object lock = new Object();
  @NonNull
  private final ArrayList<Segment> segments = new ArrayList<>();

  @Nullable
  private MediaCodec codec;
  @Nullable
  private Surface inputSurface;
  @Nullable
  private Thread drainThread;
  @Nullable
  private MediaFormat outputFormat;
  private volatile boolean running;
  private long retentionMs;
  private int width;
  private int height;
  private int frameRate;
  private int bitRate;
  @Nullable
  private Segment activeSegment;

  public boolean isRunning() {
    return running && inputSurface != null;
  }

  @Nullable
  public Surface surface() {
    return inputSurface;
  }

  public void start(int width, int height, int frameRate, int bitRate, long retentionMs) throws IOException {
    stop();

    this.width = even(Math.max(320, width));
    this.height = even(Math.max(240, height));
    this.frameRate = Math.max(1, Math.min(30, frameRate));
    this.bitRate = Math.max(400_000, bitRate);
    this.retentionMs = Math.max(1_000L, retentionMs + EXTRA_RETENTION_MS);

    MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, this.width, this.height);
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    format.setInteger(MediaFormat.KEY_BIT_RATE, this.bitRate);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, this.frameRate);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
    }

    MediaCodec newCodec = MediaCodec.createEncoderByType(MIME_TYPE);
    try {
      newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      Surface newSurface = newCodec.createInputSurface();
      synchronized (lock) {
        segments.clear();
        activeSegment = null;
        outputFormat = null;
      }
      newCodec.start();
      codec = newCodec;
      inputSurface = newSurface;
      running = true;
      drainThread = new Thread(this::drainLoop, "rolling-evidence-drain");
      drainThread.start();
    } catch (RuntimeException error) {
      try {
        newCodec.release();
      } catch (Exception ignored) {
      }
      codec = null;
      inputSurface = null;
      running = false;
      throw error;
    }
  }

  public void stop() {
    running = false;
    Thread thread = drainThread;
    drainThread = null;
    if (thread != null) {
      try {
        thread.join(500L);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    Surface surface = inputSurface;
    inputSurface = null;
    if (surface != null) {
      try {
        surface.release();
      } catch (Exception ignored) {
      }
    }

    MediaCodec oldCodec = codec;
    codec = null;
    if (oldCodec != null) {
      try {
        oldCodec.stop();
      } catch (Exception ignored) {
      }
      try {
        oldCodec.release();
      } catch (Exception ignored) {
      }
    }
  }

  @Nullable
  public File exportClip(
    @NonNull File outputDir,
    @NonNull String clipPrefix,
    long eventCapturedAtMs,
    long windowStartMs,
    long windowEndMs,
    int orientationDegrees,
    long maxBytes
  ) throws IOException {
    Snapshot snapshot = snapshot(windowStartMs, windowEndMs);
    if (snapshot == null || snapshot.samples.isEmpty()) {
      return null;
    }

    File safeOutputDir = ensureDirectory(outputDir);
    File outputFile = new File(safeOutputDir, buildClipName(clipPrefix, eventCapturedAtMs));
    if (outputFile.exists() && !outputFile.delete()) {
      throw new IOException("Failed to replace rolling evidence clip");
    }

    MediaMuxer muxer = null;
    try {
      muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int normalizedOrientation = ((orientationDegrees % 360) + 360) % 360;
      if (normalizedOrientation == 90 || normalizedOrientation == 180 || normalizedOrientation == 270) {
        muxer.setOrientationHint(normalizedOrientation);
      }
      int trackIndex = muxer.addTrack(snapshot.format);
      muxer.start();

      long basePresentationTimeUs = snapshot.samples.get(0).presentationTimeUs;
      long lastPresentationTimeUs = -1L;
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      for (EncodedSample sample : snapshot.samples) {
        long presentationTimeUs = Math.max(lastPresentationTimeUs + 1L, sample.presentationTimeUs - basePresentationTimeUs);
        bufferInfo.set(0, sample.bytes.length, presentationTimeUs, sample.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(trackIndex, ByteBuffer.wrap(sample.bytes), bufferInfo);
        lastPresentationTimeUs = presentationTimeUs;
      }
    } finally {
      if (muxer != null) {
        try {
          muxer.stop();
        } catch (Exception ignored) {
        }
        try {
          muxer.release();
        } catch (Exception ignored) {
        }
      }
    }

    if (outputFile.length() <= 0L || outputFile.length() > maxBytes) {
      if (!outputFile.delete()) {
        outputFile.deleteOnExit();
      }
      return null;
    }
    return outputFile;
  }

  @Nullable
  private Snapshot snapshot(long windowStartMs, long windowEndMs) {
    synchronized (lock) {
      if (outputFormat == null || segments.isEmpty()) {
        return null;
      }

      ArrayList<EncodedSample> samples = new ArrayList<>();
      ArrayList<Segment> candidateSegments = new ArrayList<>();
      for (Segment segment : segments) {
        if (segment.samples.isEmpty()) {
          continue;
        }
        if (segment.endCapturedAtMs >= windowStartMs - SEGMENT_DURATION_MS && segment.startCapturedAtMs <= windowEndMs) {
          candidateSegments.add(segment);
          samples.addAll(segment.samples);
        }
      }
      if (samples.isEmpty()) {
        return null;
      }

      int firstIndex = -1;
      int lastIndex = -1;
      for (int index = 0; index < samples.size(); index++) {
        EncodedSample sample = samples.get(index);
        if (sample.capturedAtMs <= windowStartMs && isSyncFrame(sample)) {
          firstIndex = index;
        }
        if (sample.capturedAtMs <= windowEndMs) {
          lastIndex = index;
        }
      }

      if (firstIndex < 0) {
        for (int index = 0; index < samples.size(); index++) {
          EncodedSample sample = samples.get(index);
          if (sample.capturedAtMs >= windowStartMs && sample.capturedAtMs <= windowEndMs && isSyncFrame(sample)) {
            firstIndex = index;
            break;
          }
        }
      }

      if (firstIndex < 0 || lastIndex < firstIndex) {
        return null;
      }

      ArrayList<EncodedSample> selected = new ArrayList<>();
      for (int index = firstIndex; index <= lastIndex; index++) {
        EncodedSample sample = samples.get(index);
        if (sample.bytes.length > 0 && (sample.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          selected.add(sample);
        }
      }
      long frozenUntilMs = System.currentTimeMillis() + FROZEN_SEGMENT_HOLD_MS;
      for (Segment segment : candidateSegments) {
        if (segment.endCapturedAtMs >= windowStartMs && segment.startCapturedAtMs <= windowEndMs) {
          segment.frozenUntilMs = Math.max(segment.frozenUntilMs, frozenUntilMs);
        }
      }
      return selected.isEmpty() ? null : new Snapshot(outputFormat, selected);
    }
  }

  private void drainLoop() {
    MediaCodec activeCodec = codec;
    if (activeCodec == null) {
      return;
    }

    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    while (running) {
      int outputIndex;
      try {
        outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US);
      } catch (IllegalStateException error) {
        break;
      }

      if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        continue;
      }
      if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        synchronized (lock) {
          outputFormat = activeCodec.getOutputFormat();
        }
        continue;
      }
      if (outputIndex < 0) {
        continue;
      }

      try {
        ByteBuffer outputBuffer = activeCodec.getOutputBuffer(outputIndex);
        if (outputBuffer != null && bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          byte[] bytes = new byte[bufferInfo.size];
          outputBuffer.position(bufferInfo.offset);
          outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
          outputBuffer.get(bytes);
          addSample(new EncodedSample(
            bytes,
            bufferInfo.presentationTimeUs,
            System.currentTimeMillis(),
            bufferInfo.flags
          ));
        }
      } finally {
        try {
          activeCodec.releaseOutputBuffer(outputIndex, false);
        } catch (Exception ignored) {
        }
      }
    }
  }

  private void addSample(@NonNull EncodedSample sample) {
    synchronized (lock) {
      if (activeSegment == null) {
        activeSegment = new Segment(sample.capturedAtMs);
        segments.add(activeSegment);
      } else if (isSyncFrame(sample) && sample.capturedAtMs - activeSegment.startCapturedAtMs >= SEGMENT_DURATION_MS) {
        activeSegment = new Segment(sample.capturedAtMs);
        segments.add(activeSegment);
      }
      activeSegment.add(sample);
      long cutoffMs = sample.capturedAtMs - retentionMs;
      long nowMs = System.currentTimeMillis();
      for (int index = segments.size() - 1; index >= 0; index--) {
        Segment segment = segments.get(index);
        if (segment == activeSegment) {
          continue;
        }
        if (segment.endCapturedAtMs < cutoffMs && segment.frozenUntilMs < nowMs) {
          segments.remove(index);
        }
      }
    }
  }

  private static boolean isSyncFrame(@NonNull EncodedSample sample) {
    return (sample.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
      || (sample.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
  }

  private static int even(int value) {
    return Math.max(2, value - (value % 2));
  }

  @NonNull
  private static File ensureDirectory(@NonNull File outputDir) throws IOException {
    if (outputDir.exists()) {
      if (!outputDir.isDirectory()) {
        throw new IOException("Rolling evidence output path is not a directory");
      }
      return outputDir;
    }
    if (!outputDir.mkdirs()) {
      throw new IOException("Failed to create rolling evidence directory");
    }
    return outputDir;
  }

  @NonNull
  private static String buildClipName(@NonNull String clipPrefix, long eventCapturedAtMs) {
    String sanitized = clipPrefix.replaceAll("[^A-Za-z0-9_-]", "_");
    if (sanitized.isEmpty()) {
      sanitized = "evidence";
    }
    return String.format(Locale.US, "%s_%d_%s.mp4", sanitized, eventCapturedAtMs, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
  }

  private static final class EncodedSample {
    @NonNull
    final byte[] bytes;
    final long presentationTimeUs;
    final long capturedAtMs;
    final int flags;

    EncodedSample(@NonNull byte[] bytes, long presentationTimeUs, long capturedAtMs, int flags) {
      this.bytes = bytes;
      this.presentationTimeUs = presentationTimeUs;
      this.capturedAtMs = capturedAtMs;
      this.flags = flags;
    }
  }

  private static final class Segment {
    final long startCapturedAtMs;
    long endCapturedAtMs;
    long frozenUntilMs;
    @NonNull
    final ArrayList<EncodedSample> samples = new ArrayList<>();

    Segment(long startCapturedAtMs) {
      this.startCapturedAtMs = startCapturedAtMs;
      this.endCapturedAtMs = startCapturedAtMs;
    }

    void add(@NonNull EncodedSample sample) {
      samples.add(sample);
      endCapturedAtMs = Math.max(endCapturedAtMs, sample.capturedAtMs);
    }
  }

  private static final class Snapshot {
    @NonNull
    final MediaFormat format;
    @NonNull
    final List<EncodedSample> samples;

    Snapshot(@NonNull MediaFormat format, @NonNull List<EncodedSample> samples) {
      this.format = format;
      this.samples = samples;
    }
  }
}
