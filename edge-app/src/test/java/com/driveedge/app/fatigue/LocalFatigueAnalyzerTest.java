package com.driveedge.app.fatigue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.driveedge.infer.yolo.DetectionResult;

import org.junit.Test;

import java.util.List;

public final class LocalFatigueAnalyzerTest {
  @Test
  public void fatigueAnalyzerOnlyTracksFatigueSignals() {
    LocalFatigueAnalyzer analyzer = new LocalFatigueAnalyzer();
    LocalFaceSignalAnalyzer.Result faceSignals =
      new LocalFaceSignalAnalyzer.Result(
        1,
        0.70f,
        0.10f,
        0.95f,
        0.85f,
        0.90f,
        0.60f,
        0.10f,
        0.20f,
        0.05f,
        0.80f,
        12
      );

    LocalFatigueAnalyzer.Result first = analyzer.analyze(faceSignals);

    assertTrue(first.drowsy);
    assertEquals("fatigue_eyes_closed", first.eventSummary);
    assertTrue(first.summaryText().contains("fatigueQuick="));
    assertTrue(first.summaryText().contains("candidate=active"));
    assertFalse(first.summaryText().contains("headPitch"));
    assertFalse(first.summaryText().contains("gaze"));
  }

  @Test
  public void fatigueAnalyzerKeepsShortFaceLossFromResettingStateImmediately() {
    LocalFatigueAnalyzer analyzer = new LocalFatigueAnalyzer(1);
    LocalFaceSignalAnalyzer.Result closedEyes =
      new LocalFaceSignalAnalyzer.Result(
        1,
        0.72f,
        0.10f,
        0.20f,
        0.10f,
        0.05f,
        0.05f,
        0.03f,
        0.02f,
        0.90f,
        0.01f,
        10
      );
    LocalFaceSignalAnalyzer.Result noFace = LocalFaceSignalAnalyzer.Result.empty(5);

    LocalFatigueAnalyzer.Result active = analyzer.analyze(closedEyes);
    LocalFatigueAnalyzer.Result bridged = analyzer.analyze(noFace);
    LocalFatigueAnalyzer.Result resumed = analyzer.analyze(closedEyes);

    assertTrue(active.drowsy);
    assertTrue(bridged.drowsy);
    assertTrue(resumed.drowsy);
    assertEquals("fatigue_eyes_closed", resumed.eventSummary);
  }

  @Test
  public void distractionAnalyzerMapsPoseSignalsIntoTemporalDetections() {
    LocalDistractionAnalyzer analyzer = new LocalDistractionAnalyzer();
    LocalFaceSignalAnalyzer.Result faceSignals =
      new LocalFaceSignalAnalyzer.Result(
        1,
        0.10f,
        0.05f,
        0.75f,
        0.65f,
        0.70f,
        0.70f,
        0.20f,
        0.40f,
        0.10f,
        0.50f,
        8
      );

    List<DetectionResult> detections = analyzer.toTemporalDetections(faceSignals, 1234L);

    assertTrue(detections.stream().anyMatch(item -> "head_down".equals(item.getLabel())));
    assertTrue(detections.stream().anyMatch(item -> "head_left".equals(item.getLabel())));
    assertTrue(detections.stream().anyMatch(item -> "look_left".equals(item.getLabel())));
    assertFalse(detections.stream().anyMatch(item -> "eye_closed".equals(item.getLabel())));
  }
}
