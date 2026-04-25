package com.driveedge.app.fatigue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.Collections;
import java.util.List;

public final class LocalFaceSignalAnalyzer implements AutoCloseable {
  private static final int FACE_NOSE_TIP = 1;
  private static final int FACE_LEFT_EYE_OUTER = 33;
  private static final int FACE_LEFT_EYE_INNER = 133;
  private static final int FACE_LEFT_EYE_UPPER = 159;
  private static final int FACE_LEFT_EYE_LOWER = 145;
  private static final int FACE_RIGHT_EYE_OUTER = 263;
  private static final int FACE_RIGHT_EYE_INNER = 362;
  private static final int FACE_RIGHT_EYE_UPPER = 386;
  private static final int FACE_RIGHT_EYE_LOWER = 374;
  private static final int FACE_CHIN = 152;

  @NonNull
  private final FaceLandmarker faceLandmarker;

  public LocalFaceSignalAnalyzer(@NonNull Context context, @NonNull String assetPath) {
    BaseOptions baseOptions = BaseOptions.builder()
      .setModelAssetPath(assetPath)
      .build();
    FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
      .setBaseOptions(baseOptions)
      .setRunningMode(RunningMode.IMAGE)
      .setNumFaces(1)
      .setMinFaceDetectionConfidence(0.4f)
      .setMinFacePresenceConfidence(0.4f)
      .setMinTrackingConfidence(0.4f)
      .setOutputFaceBlendshapes(true)
      .setOutputFacialTransformationMatrixes(true)
      .build();
    faceLandmarker = FaceLandmarker.createFromOptions(context, options);
  }

  @NonNull
  public Result analyzeBitmap(@NonNull Bitmap bitmap) {
    long startedAt = SystemClock.elapsedRealtime();
    MPImage image = new BitmapImageBuilder(bitmap).build();
    FaceLandmarkerResult result = faceLandmarker.detect(image);
    long finishedAt = SystemClock.elapsedRealtime();

    List<List<Category>> blendshapeGroups = result == null
      ? Collections.emptyList()
      : result.faceBlendshapes().orElse(Collections.emptyList());
    List<List<NormalizedLandmark>> landmarkGroups = result == null
      ? Collections.emptyList()
      : result.faceLandmarks();
    List<float[]> matrixes = result == null
      ? Collections.emptyList()
      : result.facialTransformationMatrixes().orElse(Collections.emptyList());

    if (blendshapeGroups.isEmpty() || landmarkGroups.isEmpty()) {
      return Result.empty((int) Math.max(0L, finishedAt - startedAt));
    }

    List<Category> firstFace = blendshapeGroups.get(0);
    List<NormalizedLandmark> firstLandmarks = landmarkGroups.get(0);

    float eyeBlinkLeft = scoreOf(firstFace, "eyeBlinkLeft");
    float eyeBlinkRight = scoreOf(firstFace, "eyeBlinkRight");
    float jawOpen = scoreOf(firstFace, "jawOpen");
    float eyeClosedScore = (eyeBlinkLeft + eyeBlinkRight) * 0.5f;
    PoseSignals poseSignals = estimatePoseSignals(firstFace, firstLandmarks, matrixes.isEmpty() ? null : matrixes.get(0));

    return new Result(
      1,
      eyeClosedScore,
      jawOpen,
      poseSignals.headPitchScore,
      poseSignals.headYawScore,
      poseSignals.gazeOffsetScore,
      poseSignals.gazeLeftScore,
      poseSignals.gazeRightScore,
      poseSignals.gazeDownScore,
      poseSignals.forwardScore,
      poseSignals.yawSignedScore,
      (int) Math.max(0L, finishedAt - startedAt)
    );
  }

  @Override
  public void close() {
    faceLandmarker.close();
  }

  private float scoreOf(@NonNull List<Category> categories, @NonNull String name) {
    for (Category category : categories) {
      if (name.equals(category.categoryName())) {
        return category.score();
      }
    }
    return 0f;
  }

  @NonNull
  private PoseSignals estimatePoseSignals(
    @NonNull List<Category> categories,
    @NonNull List<NormalizedLandmark> landmarks,
    @Nullable float[] transform
  ) {
    float lookDown = average(
      scoreOf(categories, "eyeLookDownLeft"),
      scoreOf(categories, "eyeLookDownRight")
    );
    float lookLeft = average(
      scoreOf(categories, "eyeLookOutLeft"),
      scoreOf(categories, "eyeLookInRight")
    );
    float lookRight = average(
      scoreOf(categories, "eyeLookInLeft"),
      scoreOf(categories, "eyeLookOutRight")
    );
    float gazeOffset = Math.max(lookDown, Math.max(lookLeft, lookRight));

    NormalizedLandmark leftEye = averageLandmark(
      landmarks,
      FACE_LEFT_EYE_OUTER,
      FACE_LEFT_EYE_INNER,
      FACE_LEFT_EYE_UPPER,
      FACE_LEFT_EYE_LOWER
    );
    NormalizedLandmark rightEye = averageLandmark(
      landmarks,
      FACE_RIGHT_EYE_OUTER,
      FACE_RIGHT_EYE_INNER,
      FACE_RIGHT_EYE_UPPER,
      FACE_RIGHT_EYE_LOWER
    );
    NormalizedLandmark nose = safeLandmark(landmarks, FACE_NOSE_TIP, leftEye);
    NormalizedLandmark chin = safeLandmark(landmarks, FACE_CHIN, nose);

    float distToLeftEye = distance(nose, leftEye);
    float distToRightEye = distance(nose, rightEye);
    float yawSigned = clamp(
      (distToRightEye - distToLeftEye) / Math.max(1e-3f, distToRightEye + distToLeftEye),
      -1f,
      1f
    );
    float headYawScore = clamp(Math.abs(yawSigned) * 2.2f, 0f, 1f);

    float eyeMidY = average(leftEye.y(), rightEye.y());
    float chinSpan = Math.max(1e-3f, chin.y() - eyeMidY);
    float noseVerticalRatio = (nose.y() - eyeMidY) / chinSpan;
    float landmarkHeadDown = clamp((noseVerticalRatio - 0.42f) / 0.22f, 0f, 1f);
    float matrixHeadDown = transform == null ? 0f : estimateHeadDownFromMatrix(transform);
    float headPitchScore = Math.max(landmarkHeadDown, matrixHeadDown);

    float forwardScore = clamp(1f - Math.max(headPitchScore, Math.max(headYawScore, gazeOffset)), 0f, 1f);
    return new PoseSignals(
      headPitchScore,
      headYawScore,
      gazeOffset,
      lookLeft,
      lookRight,
      lookDown,
      forwardScore,
      yawSigned
    );
  }

  private float estimateHeadDownFromMatrix(@NonNull float[] matrix) {
    if (matrix.length < 11) {
      return 0f;
    }
    float r21 = matrix[9];
    float r22 = matrix[10];
    float pitchRad = (float) Math.atan2(-r21, Math.max(1e-3f, Math.abs(r22)));
    return clamp(Math.abs(pitchRad) / 0.6f, 0f, 1f);
  }

  @NonNull
  private NormalizedLandmark averageLandmark(
    @NonNull List<NormalizedLandmark> landmarks,
    int... indices
  ) {
    float x = 0f;
    float y = 0f;
    float z = 0f;
    int count = 0;
    for (int index : indices) {
      if (index < 0 || index >= landmarks.size()) {
        continue;
      }
      NormalizedLandmark landmark = landmarks.get(index);
      x += landmark.x();
      y += landmark.y();
      z += landmark.z();
      count++;
    }
    if (count == 0) {
      return safeLandmark(landmarks, 0, null);
    }
    return NormalizedLandmark.create(x / count, y / count, z / count);
  }

  @NonNull
  private NormalizedLandmark safeLandmark(
    @NonNull List<NormalizedLandmark> landmarks,
    int index,
    @Nullable NormalizedLandmark fallback
  ) {
    if (index >= 0 && index < landmarks.size()) {
      return landmarks.get(index);
    }
    if (fallback != null) {
      return fallback;
    }
    return NormalizedLandmark.create(0.5f, 0.5f, 0f);
  }

  private float distance(@NonNull NormalizedLandmark a, @NonNull NormalizedLandmark b) {
    float dx = a.x() - b.x();
    float dy = a.y() - b.y();
    return (float) Math.sqrt((dx * dx) + (dy * dy));
  }

  private float average(float a, float b) {
    return (a + b) * 0.5f;
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

  private static final class PoseSignals {
    final float headPitchScore;
    final float headYawScore;
    final float gazeOffsetScore;
    final float gazeLeftScore;
    final float gazeRightScore;
    final float gazeDownScore;
    final float forwardScore;
    final float yawSignedScore;

    PoseSignals(
      float headPitchScore,
      float headYawScore,
      float gazeOffsetScore,
      float gazeLeftScore,
      float gazeRightScore,
      float gazeDownScore,
      float forwardScore,
      float yawSignedScore
    ) {
      this.headPitchScore = headPitchScore;
      this.headYawScore = headYawScore;
      this.gazeOffsetScore = gazeOffsetScore;
      this.gazeLeftScore = gazeLeftScore;
      this.gazeRightScore = gazeRightScore;
      this.gazeDownScore = gazeDownScore;
      this.forwardScore = forwardScore;
      this.yawSignedScore = yawSignedScore;
    }
  }

  public static final class Result {
    public final int faces;
    public final float eyeClosedScore;
    public final float mouthOpenScore;
    public final float headPitchScore;
    public final float headYawScore;
    public final float gazeOffsetScore;
    public final float gazeLeftScore;
    public final float gazeRightScore;
    public final float gazeDownScore;
    public final float headForwardScore;
    public final float yawSignedScore;
    public final int inferenceLatencyMs;

    Result(
      int faces,
      float eyeClosedScore,
      float mouthOpenScore,
      float headPitchScore,
      float headYawScore,
      float gazeOffsetScore,
      float gazeLeftScore,
      float gazeRightScore,
      float gazeDownScore,
      float headForwardScore,
      float yawSignedScore,
      int inferenceLatencyMs
    ) {
      this.faces = faces;
      this.eyeClosedScore = eyeClosedScore;
      this.mouthOpenScore = mouthOpenScore;
      this.headPitchScore = headPitchScore;
      this.headYawScore = headYawScore;
      this.gazeOffsetScore = gazeOffsetScore;
      this.gazeLeftScore = gazeLeftScore;
      this.gazeRightScore = gazeRightScore;
      this.gazeDownScore = gazeDownScore;
      this.headForwardScore = headForwardScore;
      this.yawSignedScore = yawSignedScore;
      this.inferenceLatencyMs = inferenceLatencyMs;
    }

    @NonNull
    static Result empty(int inferenceLatencyMs) {
      return new Result(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, inferenceLatencyMs);
    }
  }
}
