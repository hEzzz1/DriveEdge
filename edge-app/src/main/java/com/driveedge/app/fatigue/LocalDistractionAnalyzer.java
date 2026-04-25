package com.driveedge.app.fatigue;

import androidx.annotation.NonNull;

import com.driveedge.infer.yolo.BoundingBox;
import com.driveedge.infer.yolo.DetectionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LocalDistractionAnalyzer {
  @NonNull
  public List<DetectionResult> toTemporalDetections(
    @NonNull LocalFaceSignalAnalyzer.Result faceSignals,
    long frameTimestampMs
  ) {
    if (faceSignals.faces <= 0) {
      return Collections.emptyList();
    }

    List<DetectionResult> detections = new ArrayList<>(6);
    if (faceSignals.headPitchScore >= 0.15f) {
      detections.add(detection("head_down", faceSignals.headPitchScore, frameTimestampMs));
    }
    if (faceSignals.headYawScore >= 0.15f) {
      detections.add(detection(
        faceSignals.yawSignedScore >= 0f ? "head_left" : "head_right",
        faceSignals.headYawScore,
        frameTimestampMs
      ));
    }
    detections.add(detection("head_forward", Math.max(0.15f, faceSignals.headForwardScore), frameTimestampMs));

    if (faceSignals.gazeDownScore >= 0.15f) {
      detections.add(detection("look_down", faceSignals.gazeDownScore, frameTimestampMs));
    }
    if (faceSignals.gazeLeftScore >= 0.15f || faceSignals.gazeRightScore >= 0.15f) {
      detections.add(detection(
        faceSignals.gazeLeftScore >= faceSignals.gazeRightScore ? "look_left" : "look_right",
        Math.max(faceSignals.gazeLeftScore, faceSignals.gazeRightScore),
        frameTimestampMs
      ));
    } else {
      detections.add(detection("look_forward", Math.max(0.15f, 1f - faceSignals.gazeOffsetScore), frameTimestampMs));
    }
    return detections;
  }

  @NonNull
  private DetectionResult detection(
    @NonNull String label,
    float confidence,
    long frameTimestampMs
  ) {
    return new DetectionResult(
      0,
      label,
      confidence,
      new BoundingBox(0f, 0f, 1f, 1f),
      frameTimestampMs
    );
  }
}
