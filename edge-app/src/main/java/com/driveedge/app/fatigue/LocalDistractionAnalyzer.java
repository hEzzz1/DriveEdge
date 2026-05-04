package com.driveedge.app.fatigue;

import androidx.annotation.NonNull;

import com.driveedge.infer.yolo.BoundingBox;
import com.driveedge.infer.yolo.DetectionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LocalDistractionAnalyzer {
  private static final float HEAD_DOWN_CANDIDATE_THRESHOLD = 0.62f;
  private static final float HEAD_YAW_CANDIDATE_THRESHOLD = 0.78f;
  private static final float GAZE_DOWN_CANDIDATE_THRESHOLD = 0.70f;
  private static final float GAZE_SIDE_CANDIDATE_THRESHOLD = 0.82f;

  @NonNull
  public List<DetectionResult> toTemporalDetections(
    @NonNull LocalFaceSignalAnalyzer.Result faceSignals,
    long frameTimestampMs
  ) {
    if (faceSignals.faces <= 0) {
      return Collections.emptyList();
    }

    List<DetectionResult> detections = new ArrayList<>(6);
    if (faceSignals.headPitchScore >= HEAD_DOWN_CANDIDATE_THRESHOLD) {
      detections.add(detection("head_down", faceSignals.headPitchScore, frameTimestampMs));
    }
    // Short side glances are common when checking mirrors or changing lanes; only strong yaw
    // is forwarded to the temporal engine, which still requires sustained evidence.
    if (faceSignals.headYawScore >= HEAD_YAW_CANDIDATE_THRESHOLD) {
      detections.add(detection(
        faceSignals.yawSignedScore >= 0f ? "head_left" : "head_right",
        faceSignals.headYawScore,
        frameTimestampMs
      ));
    }
    detections.add(detection("head_forward", Math.max(0.15f, faceSignals.headForwardScore), frameTimestampMs));

    if (faceSignals.gazeDownScore >= GAZE_DOWN_CANDIDATE_THRESHOLD) {
      detections.add(detection("look_down", faceSignals.gazeDownScore, frameTimestampMs));
    }
    if (faceSignals.gazeLeftScore >= GAZE_SIDE_CANDIDATE_THRESHOLD || faceSignals.gazeRightScore >= GAZE_SIDE_CANDIDATE_THRESHOLD) {
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
