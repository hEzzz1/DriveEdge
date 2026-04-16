package com.driveedge.app.fatigue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LocalOnnxDetector implements AutoCloseable {
  @NonNull
  private final OrtEnvironment environment;
  @NonNull
  private final OrtSession session;
  @NonNull
  private final String inputName;
  @NonNull
  private final String outputName;
  private final int inputWidth;
  private final int inputHeight;
  private final float confThreshold;
  private final float nmsThreshold;

  public LocalOnnxDetector(
    @NonNull Context context,
    @NonNull String assetModelPath,
    float confThreshold,
    float nmsThreshold
  ) throws Exception {
    this.environment = OrtEnvironment.getEnvironment();
    this.confThreshold = confThreshold;
    this.nmsThreshold = nmsThreshold;

    File modelFile = ensureModelFile(context, assetModelPath);
    OrtSession createdSession;
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
      options.setIntraOpNumThreads(1);
      createdSession = environment.createSession(modelFile.getAbsolutePath(), options);
    }
    this.session = createdSession;
    this.inputName = session.getInputNames().iterator().next();
    this.outputName = session.getOutputNames().iterator().next();
    TensorInfo inputInfo = resolveInputTensorInfo(session, inputName);
    int[] resolved = resolveModelInputSize(inputInfo);
    this.inputWidth = resolved[0];
    this.inputHeight = resolved[1];
  }

  @NonNull
  public Result inferJpeg(@NonNull byte[] jpeg) throws Exception {
    Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    if (bitmap == null) {
      throw new IllegalStateException("Failed to decode JPEG bytes");
    }
    try {
      return inferBitmap(bitmap);
    } finally {
      bitmap.recycle();
    }
  }

  @NonNull
  public Result inferBitmap(@NonNull Bitmap bitmap) throws Exception {
    long startedAt = System.currentTimeMillis();
    int originalWidth = bitmap.getWidth();
    int originalHeight = bitmap.getHeight();
    float[] input = preprocessBitmap(bitmap);

    long inferStartedAt = System.currentTimeMillis();
    try (
      OnnxTensor inputTensor = OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(input),
        new long[] {1, 3, inputHeight, inputWidth}
      );
      OrtSession.Result output = session.run(Collections.singletonMap(inputName, inputTensor))
    ) {
      OnnxTensor outputTensor = null;
      if (outputName != null) {
        outputTensor = (OnnxTensor) output.get(outputName).orElse(null);
      }
      if (outputTensor == null) {
        outputTensor = (OnnxTensor) output.get(0);
      }
      if (outputTensor == null) {
        throw new IllegalStateException("ONNX output tensor is null");
      }

      DecodedOutput decoded = decodeOutput(outputTensor);
      List<Detection> kept = nmsPerClass(decoded.candidates, nmsThreshold);

      float maxScore = 0f;
      for (Detection detection : kept) {
        if (detection.score > maxScore) {
          maxScore = detection.score;
        }
      }
      List<Box> boxes = new ArrayList<>(kept.size());
      float scaleX = originalWidth / (float) inputWidth;
      float scaleY = originalHeight / (float) inputHeight;
      for (Detection detection : kept) {
        boxes.add(new Box(
          clamp(detection.x1 * scaleX, 0f, originalWidth),
          clamp(detection.y1 * scaleY, 0f, originalHeight),
          clamp(detection.x2 * scaleX, 0f, originalWidth),
          clamp(detection.y2 * scaleY, 0f, originalHeight),
          detection.score,
          detection.classId
        ));
      }

      long finishedAt = System.currentTimeMillis();
      return new Result(
        kept.size(),
        maxScore,
        (int) Math.max(0L, finishedAt - startedAt),
        (int) Math.max(0L, finishedAt - inferStartedAt),
        decoded.shapeText,
        boxes
      );
    }
  }

  public int getInputWidth() {
    return inputWidth;
  }

  public int getInputHeight() {
    return inputHeight;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (Exception ignored) {
      // no-op
    }
  }

  @NonNull
  private float[] preprocessBitmap(@NonNull Bitmap bitmap) {
    Bitmap scaled = bitmap;
    if (bitmap.getWidth() != inputWidth || bitmap.getHeight() != inputHeight) {
      scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
    }

    int pixelCount = inputWidth * inputHeight;
    int[] pixels = new int[pixelCount];
    scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

    if (scaled != bitmap) {
      scaled.recycle();
    }

    float[] chw = new float[3 * pixelCount];
    int planeSize = pixelCount;
    for (int i = 0; i < pixelCount; i++) {
      int pixel = pixels[i];
      float r = ((pixel >> 16) & 0xFF) / 255.0f;
      float g = ((pixel >> 8) & 0xFF) / 255.0f;
      float b = (pixel & 0xFF) / 255.0f;
      chw[i] = r;
      chw[planeSize + i] = g;
      chw[(planeSize * 2) + i] = b;
    }
    return chw;
  }

  @NonNull
  private DecodedOutput decodeOutput(@NonNull OnnxTensor tensor) throws Exception {
    TensorInfo tensorInfo = (TensorInfo) tensor.getInfo();
    long[] shape = tensorInfo.getShape();
    if (shape.length != 3) {
      throw new IllegalStateException("Unexpected output rank=" + shape.length);
    }

    int dim1 = safeToInt(shape[1], "shape[1]");
    int dim2 = safeToInt(shape[2], "shape[2]");
    String shapeText = String.format(Locale.US, "1x%dx%d", dim1, dim2);

    FloatBuffer floatBuffer = tensor.getFloatBuffer();
    float[] values = new float[floatBuffer.remaining()];
    floatBuffer.get(values);

    boolean channelFirst = dim1 <= 64 && dim2 >= dim1;
    int channels = channelFirst ? dim1 : dim2;
    int boxCount = channelFirst ? dim2 : dim1;
    if (channels < 5) {
      return new DecodedOutput(new ArrayList<>(), shapeText);
    }

    List<Detection> candidates = new ArrayList<>();
    for (int i = 0; i < boxCount; i++) {
      float cx = readValue(values, channelFirst, channels, boxCount, 0, i);
      float cy = readValue(values, channelFirst, channels, boxCount, 1, i);
      float w = readValue(values, channelFirst, channels, boxCount, 2, i);
      float h = readValue(values, channelFirst, channels, boxCount, 3, i);

      if (!(w > 0f && h > 0f)) {
        continue;
      }

      float bestScore = 0f;
      int bestClassId = -1;
      for (int c = 4; c < channels; c++) {
        float score = readValue(values, channelFirst, channels, boxCount, c, i);
        if (score > bestScore) {
          bestScore = score;
          bestClassId = c - 4;
        }
      }

      if (bestScore < confThreshold) {
        continue;
      }

      float x1 = clamp(cx - (w * 0.5f), 0f, inputWidth);
      float y1 = clamp(cy - (h * 0.5f), 0f, inputHeight);
      float x2 = clamp(cx + (w * 0.5f), 0f, inputWidth);
      float y2 = clamp(cy + (h * 0.5f), 0f, inputHeight);
      if (x2 <= x1 || y2 <= y1) {
        continue;
      }
      candidates.add(new Detection(x1, y1, x2, y2, bestScore, bestClassId));
    }
    return new DecodedOutput(candidates, shapeText);
  }

  @NonNull
  private TensorInfo resolveInputTensorInfo(@NonNull OrtSession ortSession, @NonNull String name) throws OrtException {
    NodeInfo nodeInfo = ortSession.getInputInfo().get(name);
    if (nodeInfo == null) {
      throw new IllegalStateException("Input node not found: " + name);
    }
    if (!(nodeInfo.getInfo() instanceof TensorInfo)) {
      throw new IllegalStateException("Input node is not TensorInfo: " + name);
    }
    return (TensorInfo) nodeInfo.getInfo();
  }

  @NonNull
  private int[] resolveModelInputSize(@NonNull TensorInfo tensorInfo) {
    long[] shape = tensorInfo.getShape();
    if (shape.length != 4) {
      throw new IllegalStateException("Unexpected input rank=" + shape.length);
    }
    int h = safeToInt(shape[2], "input.shape[2]");
    int w = safeToInt(shape[3], "input.shape[3]");
    return new int[] {w, h};
  }

  private int safeToInt(long value, @NonNull String label) {
    if (value <= 0L || value > Integer.MAX_VALUE) {
      throw new IllegalStateException("Invalid " + label + "=" + value);
    }
    return (int) value;
  }

  private float readValue(
    @NonNull float[] values,
    boolean channelFirst,
    int channels,
    int boxCount,
    int channel,
    int boxIndex
  ) {
    if (channelFirst) {
      return values[(channel * boxCount) + boxIndex];
    }
    return values[(boxIndex * channels) + channel];
  }

  @NonNull
  private List<Detection> nmsPerClass(@NonNull List<Detection> candidates, float threshold) {
    if (candidates.isEmpty()) {
      return candidates;
    }

    Map<Integer, List<Detection>> grouped = new HashMap<>();
    for (Detection candidate : candidates) {
      List<Detection> sameClass = grouped.get(candidate.classId);
      if (sameClass == null) {
        sameClass = new ArrayList<>();
        grouped.put(candidate.classId, sameClass);
      }
      sameClass.add(candidate);
    }

    List<Detection> merged = new ArrayList<>(Math.min(candidates.size(), 128));
    for (List<Detection> sameClassCandidates : grouped.values()) {
      sameClassCandidates.sort(Comparator.comparingDouble(item -> -item.score));
      for (Detection candidate : sameClassCandidates) {
        boolean suppressed = false;
        for (Detection selected : merged) {
          if (selected.classId != candidate.classId) {
            continue;
          }
          if (iou(candidate, selected) > threshold) {
            suppressed = true;
            break;
          }
        }
        if (!suppressed) {
          merged.add(candidate);
        }
      }
    }
    merged.sort(Comparator.comparingDouble(item -> -item.score));
    if (merged.size() > 128) {
      return new ArrayList<>(merged.subList(0, 128));
    }
    return merged;
  }

  private float iou(@NonNull Detection a, @NonNull Detection b) {
    float interLeft = Math.max(a.x1, b.x1);
    float interTop = Math.max(a.y1, b.y1);
    float interRight = Math.min(a.x2, b.x2);
    float interBottom = Math.min(a.y2, b.y2);

    float interW = Math.max(0f, interRight - interLeft);
    float interH = Math.max(0f, interBottom - interTop);
    float interArea = interW * interH;
    if (interArea <= 0f) {
      return 0f;
    }

    float areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
    float areaB = (b.x2 - b.x1) * (b.y2 - b.y1);
    float unionArea = areaA + areaB - interArea;
    if (unionArea <= 0f) {
      return 0f;
    }
    return interArea / unionArea;
  }

  private float clamp(float value, float min, float max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
  }

  @NonNull
  private File ensureModelFile(@NonNull Context context, @NonNull String assetModelPath) throws Exception {
    File modelDir = new File(context.getFilesDir(), "models");
    if (!modelDir.exists() && !modelDir.mkdirs()) {
      throw new IllegalStateException("Failed to create model directory");
    }

    String modelName = new File(assetModelPath).getName();
    File modelFile = new File(modelDir, modelName);
    if (modelFile.exists() && modelFile.length() > 0L) {
      return modelFile;
    }

    try (
      InputStream inputStream = context.getAssets().open(assetModelPath);
      FileOutputStream outputStream = new FileOutputStream(modelFile, false)
    ) {
      byte[] buffer = new byte[16 * 1024];
      int read;
      while ((read = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, read);
      }
      outputStream.flush();
    }

    return modelFile;
  }

  private static final class Detection {
    final float x1;
    final float y1;
    final float x2;
    final float y2;
    final float score;
    final int classId;

    Detection(float x1, float y1, float x2, float y2, float score, int classId) {
      this.x1 = x1;
      this.y1 = y1;
      this.x2 = x2;
      this.y2 = y2;
      this.score = score;
      this.classId = classId;
    }
  }

  private static final class DecodedOutput {
    @NonNull
    final List<Detection> candidates;
    @NonNull
    final String shapeText;

    DecodedOutput(@NonNull List<Detection> candidates, @NonNull String shapeText) {
      this.candidates = candidates;
      this.shapeText = shapeText;
    }
  }

  public static final class Result {
    public final int detections;
    public final float maxScore;
    public final int totalLatencyMs;
    public final int inferenceLatencyMs;
    @NonNull
    public final String outputShape;
    @NonNull
    public final List<Box> boxes;

    Result(
      int detections,
      float maxScore,
      int totalLatencyMs,
      int inferenceLatencyMs,
      @NonNull String outputShape,
      @NonNull List<Box> boxes
    ) {
      this.detections = detections;
      this.maxScore = maxScore;
      this.totalLatencyMs = totalLatencyMs;
      this.inferenceLatencyMs = inferenceLatencyMs;
      this.outputShape = outputShape;
      this.boxes = boxes;
    }
  }

  public static final class Box {
    public final float left;
    public final float top;
    public final float right;
    public final float bottom;
    public final float score;
    public final int classId;

    Box(float left, float top, float right, float bottom, float score, int classId) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.score = score;
      this.classId = classId;
    }
  }
}
