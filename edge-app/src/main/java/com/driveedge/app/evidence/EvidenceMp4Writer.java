package com.driveedge.app.evidence;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class EvidenceMp4Writer {
  private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
  private static final int MAX_DIMENSION = 640;
  private static final int MIN_DIMENSION = 160;
  private static final long TIMEOUT_US = 10_000L;

  @Nullable
  public File writeClip(
    @NonNull File outputDir,
    @NonNull String clipPrefix,
    @NonNull List<EvidenceFrameBuffer.EvidenceFrame> frames,
    long eventCapturedAtMs,
    long maxBytes,
    int requestedFrameRate
  ) throws IOException {
    if (frames.isEmpty()) {
      return null;
    }

    File safeOutputDir = ensureDirectory(outputDir);
    List<EvidenceFrameBuffer.EvidenceFrame> workingFrames = new ArrayList<>(frames);
    int frameRate = Math.max(1, Math.min(30, requestedFrameRate));
    File outputFile;
    while (!workingFrames.isEmpty()) {
      outputFile = new File(safeOutputDir, buildClipName(clipPrefix, eventCapturedAtMs));
      encodeMp4(outputFile, workingFrames, frameRate);
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

  private void encodeMp4(
    @NonNull File outputFile,
    @NonNull List<EvidenceFrameBuffer.EvidenceFrame> frames,
    int frameRate
  ) throws IOException {
    File parent = outputFile.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Failed to create evidence video directory");
    }
    if (outputFile.exists() && !outputFile.delete()) {
      throw new IOException("Failed to replace existing evidence video");
    }

    VideoSize size = resolveVideoSize(frames.get(0));
    CodecChoice codecChoice = selectAvcEncoder();
    if (codecChoice == null) {
      throw new IOException("No AVC encoder available");
    }

    MediaCodec codec = null;
    MediaMuxer muxer = null;
    try {
      MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, size.width, size.height);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, codecChoice.colorFormat);
      format.setInteger(MediaFormat.KEY_BIT_RATE, resolveBitrate(size.width, size.height, frameRate));
      format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

      codec = MediaCodec.createByCodecName(codecChoice.codecName);
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start();
      muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

      EncoderState state = new EncoderState();
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      long firstCapturedAtMs = frames.get(0).capturedAtMs();
      long fallbackFrameDurationUs = 1_000_000L / Math.max(1, frameRate);
      long lastPresentationTimeUs = -fallbackFrameDurationUs;

      for (int index = 0; index < frames.size(); index++) {
        EvidenceFrameBuffer.EvidenceFrame frame = frames.get(index);
        byte[] yuv = decodeFrameToYuv(frame, size.width, size.height, codecChoice.colorFormat);
        long capturedDeltaUs = Math.max(0L, frame.capturedAtMs() - firstCapturedAtMs) * 1_000L;
        long presentationTimeUs = Math.max(lastPresentationTimeUs + 1L, capturedDeltaUs);
        queueFrame(codec, yuv, presentationTimeUs);
        lastPresentationTimeUs = presentationTimeUs;
        drainEncoder(codec, muxer, state, bufferInfo, false);
      }

      queueEndOfStream(codec, lastPresentationTimeUs + fallbackFrameDurationUs);
      drainEncoder(codec, muxer, state, bufferInfo, true);
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("Failed to encode evidence mp4", error);
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (Exception ignored) {
        }
        try {
          codec.release();
        } catch (Exception ignored) {
        }
      }
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
  }

  private void queueFrame(
    @NonNull MediaCodec codec,
    @NonNull byte[] yuv,
    long presentationTimeUs
  ) throws IOException {
    int inputBufferIndex = dequeueInputBuffer(codec);
    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
    if (inputBuffer == null || inputBuffer.capacity() < yuv.length) {
      throw new IOException("Encoder input buffer is too small");
    }
    inputBuffer.clear();
    inputBuffer.put(yuv);
    codec.queueInputBuffer(inputBufferIndex, 0, yuv.length, presentationTimeUs, 0);
  }

  private void queueEndOfStream(@NonNull MediaCodec codec, long presentationTimeUs) throws IOException {
    int inputBufferIndex = dequeueInputBuffer(codec);
    codec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
  }

  private int dequeueInputBuffer(@NonNull MediaCodec codec) throws IOException {
    for (int attempt = 0; attempt < 50; attempt++) {
      int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
      if (inputBufferIndex >= 0) {
        return inputBufferIndex;
      }
    }
    throw new IOException("Timed out waiting for encoder input buffer");
  }

  private void drainEncoder(
    @NonNull MediaCodec codec,
    @NonNull MediaMuxer muxer,
    @NonNull EncoderState state,
    @NonNull MediaCodec.BufferInfo bufferInfo,
    boolean endOfStream
  ) throws IOException {
    while (true) {
      int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
      if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (!endOfStream) {
          return;
        }
        continue;
      }
      if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (state.muxerStarted) {
          throw new IOException("Encoder output format changed twice");
        }
        state.trackIndex = muxer.addTrack(codec.getOutputFormat());
        muxer.start();
        state.muxerStarted = true;
        continue;
      }
      if (outputBufferIndex < 0) {
        continue;
      }

      ByteBuffer encodedData = codec.getOutputBuffer(outputBufferIndex);
      if (encodedData == null) {
        throw new IOException("Encoder output buffer is null");
      }
      if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        bufferInfo.size = 0;
      }
      if (bufferInfo.size > 0) {
        if (!state.muxerStarted) {
          throw new IOException("Muxer has not started");
        }
        encodedData.position(bufferInfo.offset);
        encodedData.limit(bufferInfo.offset + bufferInfo.size);
        muxer.writeSampleData(state.trackIndex, encodedData, bufferInfo);
      }

      codec.releaseOutputBuffer(outputBufferIndex, false);
      if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
        return;
      }
    }
  }

  @NonNull
  private byte[] decodeFrameToYuv(
    @NonNull EvidenceFrameBuffer.EvidenceFrame frame,
    int targetWidth,
    int targetHeight,
    int colorFormat
  ) throws IOException {
    Bitmap decoded = BitmapFactory.decodeByteArray(frame.jpegBytes(), 0, frame.jpegBytes().length);
    if (decoded == null) {
      throw new IOException("Failed to decode evidence frame");
    }
    Bitmap fitted = null;
    try {
      fitted = fitBitmap(decoded, targetWidth, targetHeight);
      return bitmapToYuv420(fitted, colorFormat);
    } finally {
      if (fitted != null && fitted != decoded) {
        fitted.recycle();
      }
      decoded.recycle();
    }
  }

  @NonNull
  private Bitmap fitBitmap(@NonNull Bitmap source, int targetWidth, int targetHeight) {
    Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.drawColor(Color.BLACK);

    float scale = Math.min(targetWidth / (float) source.getWidth(), targetHeight / (float) source.getHeight());
    int drawWidth = Math.max(1, Math.round(source.getWidth() * scale));
    int drawHeight = Math.max(1, Math.round(source.getHeight() * scale));
    float left = (targetWidth - drawWidth) / 2f;
    float top = (targetHeight - drawHeight) / 2f;
    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    canvas.drawBitmap(source, null, new RectF(left, top, left + drawWidth, top + drawHeight), paint);
    return output;
  }

  @NonNull
  private byte[] bitmapToYuv420(@NonNull Bitmap bitmap, int colorFormat) {
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int frameSize = width * height;
    int chromaSize = frameSize / 4;
    byte[] yuv = new byte[frameSize + (chromaSize * 2)];
    int[] pixels = new int[frameSize];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

    for (int y = 0; y < height; y++) {
      int row = y * width;
      for (int x = 0; x < width; x++) {
        int argb = pixels[row + x];
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int yValue = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        yuv[row + x] = (byte) yValue;
      }
    }

    boolean semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    int uPlaneOffset = frameSize;
    int vPlaneOffset = frameSize + chromaSize;
    int uvOffset = frameSize;
    for (int y = 0; y < height; y += 2) {
      for (int x = 0; x < width; x += 2) {
        int argb = pixels[y * width + x];
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int uValue = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
        int vValue = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        if (semiPlanar) {
          yuv[uvOffset++] = (byte) uValue;
          yuv[uvOffset++] = (byte) vValue;
        } else {
          int chromaIndex = (y / 2) * (width / 2) + (x / 2);
          yuv[uPlaneOffset + chromaIndex] = (byte) uValue;
          yuv[vPlaneOffset + chromaIndex] = (byte) vValue;
        }
      }
    }
    return yuv;
  }

  private int clamp(int value) {
    if (value < 0) {
      return 0;
    }
    if (value > 255) {
      return 255;
    }
    return value;
  }

  @Nullable
  private CodecChoice selectAvcEncoder() {
    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    for (MediaCodecInfo codecInfo : mediaCodecList.getCodecInfos()) {
      if (!codecInfo.isEncoder()) {
        continue;
      }
      String codecName = codecInfo.getName();
      for (String type : codecInfo.getSupportedTypes()) {
        if (!MIME_TYPE.equalsIgnoreCase(type)) {
          continue;
        }
        int colorFormat = selectColorFormat(codecInfo.getCapabilitiesForType(type).colorFormats);
        if (colorFormat != 0) {
          return new CodecChoice(codecName, colorFormat);
        }
      }
    }
    return null;
  }

  private int selectColorFormat(@NonNull int[] colorFormats) {
    for (int colorFormat : colorFormats) {
      if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
        return colorFormat;
      }
    }
    for (int colorFormat : colorFormats) {
      if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
        return colorFormat;
      }
    }
    return 0;
  }

  private int resolveBitrate(int width, int height, int frameRate) {
    int calculated = (width * height * Math.max(1, frameRate)) / 2;
    return Math.max(600_000, Math.min(2_500_000, calculated));
  }

  @NonNull
  private VideoSize resolveVideoSize(@NonNull EvidenceFrameBuffer.EvidenceFrame frame) throws IOException {
    if (frame.width() <= 0 || frame.height() <= 0) {
      throw new IOException("Invalid evidence frame size");
    }
    float scale = Math.min(1f, MAX_DIMENSION / (float) Math.max(frame.width(), frame.height()));
    int width = even(Math.max(MIN_DIMENSION, Math.round(frame.width() * scale)));
    int height = even(Math.max(MIN_DIMENSION, Math.round(frame.height() * scale)));
    return new VideoSize(width, height);
  }

  private int even(int value) {
    return Math.max(2, value - (value % 2));
  }

  @NonNull
  private File ensureDirectory(@NonNull File outputDir) throws IOException {
    if (outputDir.exists()) {
      if (!outputDir.isDirectory()) {
        throw new IOException("Evidence video output path is not a directory");
      }
      return outputDir;
    }
    if (!outputDir.mkdirs()) {
      throw new IOException("Failed to create evidence video directory");
    }
    return outputDir;
  }

  @NonNull
  private String buildClipName(@NonNull String clipPrefix, long eventCapturedAtMs) {
    String sanitized = sanitizePrefix(clipPrefix);
    return String.format(Locale.US, "%s_%d_%s.mp4", sanitized, eventCapturedAtMs, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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
    return builder.length() == 0 ? "evidence" : builder.toString();
  }

  private static final class CodecChoice {
    @NonNull
    final String codecName;
    final int colorFormat;

    CodecChoice(@NonNull String codecName, int colorFormat) {
      this.codecName = codecName;
      this.colorFormat = colorFormat;
    }
  }

  private static final class EncoderState {
    int trackIndex = -1;
    boolean muxerStarted = false;
  }

  private static final class VideoSize {
    final int width;
    final int height;

    VideoSize(int width, int height) {
      this.width = width;
      this.height = height;
    }
  }
}
