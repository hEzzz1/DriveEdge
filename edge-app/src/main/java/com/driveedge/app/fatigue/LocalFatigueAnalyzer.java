package com.driveedge.app.fatigue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LocalFatigueAnalyzer implements AutoCloseable {
  @NonNull
  private final FaceLandmarker faceLandmarker;
  private int closedEyeFrames = 0;
  private int yawnFrames = 0;

  public LocalFatigueAnalyzer(@NonNull Context context, @NonNull String assetPath) {
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
    if (blendshapeGroups == null || blendshapeGroups.isEmpty()) {
      closedEyeFrames = 0;
      yawnFrames = 0;
      return new Result(0, 0f, 0f, false, false, false, 0f, "-", (int) Math.max(0L, finishedAt - startedAt));
    }

    List<Category> firstFace = blendshapeGroups.get(0);
    float eyeBlinkLeft = scoreOf(firstFace, "eyeBlinkLeft");
    float eyeBlinkRight = scoreOf(firstFace, "eyeBlinkRight");
    float jawOpen = scoreOf(firstFace, "jawOpen");
    float eyeClosedScore = (eyeBlinkLeft + eyeBlinkRight) * 0.5f;

    boolean eyesClosed = eyeClosedScore >= 0.55f;
    boolean yawning = jawOpen >= 0.35f;

    if (eyesClosed) {
      closedEyeFrames++;
    } else {
      closedEyeFrames = 0;
    }
    if (yawning) {
      yawnFrames++;
    } else {
      yawnFrames = 0;
    }

    boolean drowsy = closedEyeFrames >= 3 || yawnFrames >= 3;
    float fatigueScore = Math.max(eyeClosedScore, jawOpen);
    String eventSummary;
    if (closedEyeFrames >= 3) {
      eventSummary = "eyes_closed";
    } else if (yawnFrames >= 3) {
      eventSummary = "yawning";
    } else if (eyesClosed) {
      eventSummary = "blink_like";
    } else if (yawning) {
      eventSummary = "mouth_open";
    } else {
      eventSummary = "normal";
    }

      return new Result(
        1,
        eyeClosedScore,
        jawOpen,
        eyesClosed,
        yawning,
        drowsy,
        fatigueScore,
        eventSummary,
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

  public static final class Result {
    public final int faces;
    public final float eyeClosedScore;
    public final float mouthOpenScore;
    public final boolean eyesClosed;
    public final boolean yawning;
    public final boolean drowsy;
    public final float fatigueScore;
    @NonNull
    public final String eventSummary;
    public final int inferenceLatencyMs;

    Result(
      int faces,
      float eyeClosedScore,
      float mouthOpenScore,
      boolean eyesClosed,
      boolean yawning,
      boolean drowsy,
      float fatigueScore,
      @NonNull String eventSummary,
      int inferenceLatencyMs
    ) {
      this.faces = faces;
      this.eyeClosedScore = eyeClosedScore;
      this.mouthOpenScore = mouthOpenScore;
      this.eyesClosed = eyesClosed;
      this.yawning = yawning;
      this.drowsy = drowsy;
      this.fatigueScore = fatigueScore;
      this.eventSummary = eventSummary;
      this.inferenceLatencyMs = inferenceLatencyMs;
    }

    @NonNull
    public String summaryText() {
      return String.format(
        Locale.US,
        "fatigue=%.2f eyes=%.2f mouth=%.2f alert=%s event=%s",
        fatigueScore,
        eyeClosedScore,
        mouthOpenScore,
        drowsy ? "warning" : "normal",
        eventSummary
      );
    }
  }
}
