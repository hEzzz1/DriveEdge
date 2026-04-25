package com.driveedge.app.fatigue;

import androidx.annotation.NonNull;

import java.util.Locale;

public final class LocalFatigueAnalyzer implements AutoCloseable {
  private final int missingFaceToleranceFrames;
  private int missingFaceFrames = 0;
  private float lastEyeClosedScore = 0f;
  private float lastMouthOpenScore = 0f;
  private boolean lastEyesClosed = false;
  private boolean lastYawning = false;
  @NonNull
  private String lastEventSummary = "fatigue_normal";

  public LocalFatigueAnalyzer() {
    this(2);
  }

  public LocalFatigueAnalyzer(int missingFaceToleranceFrames) {
    if (missingFaceToleranceFrames < 0) {
      throw new IllegalArgumentException("missingFaceToleranceFrames must be >= 0");
    }
    this.missingFaceToleranceFrames = missingFaceToleranceFrames;
  }

  @NonNull
  public Result analyze(@NonNull LocalFaceSignalAnalyzer.Result faceSignals) {
    if (faceSignals.faces <= 0) {
      missingFaceFrames++;
      if (missingFaceFrames > missingFaceToleranceFrames) {
        resetState();
        return Result.empty(faceSignals.inferenceLatencyMs);
      }
      return new Result(
        0,
        lastEyeClosedScore,
        lastMouthOpenScore,
        lastEyesClosed,
        lastYawning,
        isDrowsy(),
        Math.max(lastEyeClosedScore, lastMouthOpenScore),
        lastEventSummary,
        faceSignals.inferenceLatencyMs
      );
    }
    missingFaceFrames = 0;

    float eyeClosedScore = faceSignals.eyeClosedScore;
    float jawOpen = faceSignals.mouthOpenScore;

    boolean eyesClosed = eyeClosedScore >= 0.55f;
    boolean yawning = jawOpen >= 0.35f;

    boolean drowsy = eyesClosed || yawning;
    float fatigueScore = Math.max(eyeClosedScore, jawOpen);
    String eventSummary;
    if (eyesClosed) {
      eventSummary = "fatigue_eyes_closed";
    } else if (yawning) {
      eventSummary = "fatigue_yawning";
    } else {
      eventSummary = "fatigue_normal";
    }
    lastEyeClosedScore = eyeClosedScore;
    lastMouthOpenScore = jawOpen;
    lastEyesClosed = eyesClosed;
    lastYawning = yawning;
    lastEventSummary = eventSummary;

    return new Result(
      1,
      eyeClosedScore,
      jawOpen,
      eyesClosed,
      yawning,
      drowsy,
      fatigueScore,
      eventSummary,
      faceSignals.inferenceLatencyMs
    );
  }

  @Override
  public void close() {
  }

  private boolean isDrowsy() {
    return lastEyesClosed || lastYawning;
  }

  private void resetState() {
    missingFaceFrames = 0;
    lastEyeClosedScore = 0f;
    lastMouthOpenScore = 0f;
    lastEyesClosed = false;
    lastYawning = false;
    lastEventSummary = "fatigue_normal";
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
        "fatigueQuick=%.2f eyes=%.2f mouth=%.2f candidate=%s event=%s",
        fatigueScore,
        eyeClosedScore,
        mouthOpenScore,
        drowsy ? "active" : "clear",
        eventSummary
      );
    }

    @NonNull
    static Result empty(int inferenceLatencyMs) {
      return new Result(0, 0f, 0f, false, false, false, 0f, "fatigue_normal", inferenceLatencyMs);
    }
  }
}
