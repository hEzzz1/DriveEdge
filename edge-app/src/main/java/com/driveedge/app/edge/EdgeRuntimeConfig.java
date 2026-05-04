package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.driveedge.risk.engine.DistractionWeights;
import com.driveedge.risk.engine.FatigueWeights;
import com.driveedge.risk.engine.RiskEngineConfig;
import com.driveedge.temporal.engine.TemporalEngineConfig;

import org.json.JSONObject;

public final class EdgeRuntimeConfig {
  private static final long DEFAULT_TEMPORAL_WINDOW_MS = 4_000L;
  private static final int DEFAULT_SMOOTHING_WINDOW_COUNT = 3;
  private static final int DEFAULT_STABLE_WINDOW_HIT_COUNT = 2;
  private static final int DEFAULT_CLEAR_WINDOW_COUNT = 2;
  private static final double DEFAULT_FEATURE_EMA_ALPHA = 0.65;
  private static final long DEFAULT_BLINK_MIN_DURATION_MS = 80L;
  private static final long DEFAULT_BLINK_MAX_DURATION_MS = 600L;
  private static final long DEFAULT_YAWN_MIN_DURATION_MS = 700L;
  private static final float DEFAULT_MIN_SIGNAL_CONFIDENCE = 0.35f;
  private static final double DEFAULT_FATIGUE_PERCLOS_THRESHOLD = 0.24;
  private static final long DEFAULT_FATIGUE_PERCLOS_DURATION_MS = 2_000L;
  private static final int DEFAULT_FATIGUE_YAWN_COUNT_THRESHOLD = 1;
  private static final long DEFAULT_FATIGUE_YAWN_WINDOW_MAX_MS = 30_000L;
  private static final long DEFAULT_DISTRACTION_HEAD_POSE_DURATION_MS = 1_500L;
  private static final double DEFAULT_DISTRACTION_HEAD_POSE_STABILITY_THRESHOLD = 0.50;
  private static final double DEFAULT_DISTRACTION_HEAD_DOWN_THRESHOLD = 0.45;
  private static final long DEFAULT_DISTRACTION_HEAD_DOWN_DURATION_MS = 1_000L;
  private static final double DEFAULT_DISTRACTION_GAZE_OFFSET_THRESHOLD = 0.42;
  private static final long DEFAULT_DISTRACTION_GAZE_OFFSET_DURATION_MS = 1_000L;
  private static final int DEFAULT_TRIGGER_CONFIRM_COUNT = 2;
  private static final int DEFAULT_CLEAR_CONFIRM_COUNT = 2;
  private static final double DEFAULT_TRIGGER_HYSTERESIS_DELTA = 0.03;
  private static final double DEFAULT_CLEAR_HYSTERESIS_DELTA = 0.08;
  private static final double DEFAULT_LOW_RISK_THRESHOLD = 0.48;
  private static final double DEFAULT_MEDIUM_RISK_THRESHOLD = 0.65;
  private static final double DEFAULT_HIGH_RISK_THRESHOLD = 0.80;
  private static final long DEFAULT_DEBOUNCE_WINDOW_MS = 8_000L;
  private static final int DEFAULT_EVIDENCE_JPEG_QUALITY = 68;
  private static final int DEFAULT_EVIDENCE_MAX_BYTES = 8 * 1024 * 1024;
  private static final long DEFAULT_EVIDENCE_SEQUENCE_WINDOW_MS = 4_000L;
  private static final long DEFAULT_EVIDENCE_POST_WINDOW_MS = 1_000L;
  private static final long DEFAULT_EVIDENCE_SEQUENCE_SAMPLE_INTERVAL_MS = 33L;
  private static final int DEFAULT_EVIDENCE_SEQUENCE_MAX_FRAMES = 150;

  @Nullable
  private final String configVersion;
  private final long temporalWindowSizeMs;
  private final int smoothingWindowCount;
  private final int stableWindowHitCount;
  private final int clearWindowCount;
  private final double featureEmaAlpha;
  private final long blinkMinDurationMs;
  private final long blinkMaxDurationMs;
  private final long yawnMinDurationMs;
  private final float minSignalConfidence;
  private final double fatiguePerclosThreshold;
  private final long fatiguePerclosDurationMs;
  private final int fatigueYawnCountThreshold;
  private final long fatigueYawnWindowMaxMs;
  private final long distractionHeadPoseDurationMs;
  private final double distractionHeadPoseStabilityThreshold;
  private final double distractionHeadDownThreshold;
  private final long distractionHeadDownDurationMs;
  private final double distractionGazeOffsetThreshold;
  private final long distractionGazeOffsetDurationMs;
  private final int triggerConfirmCount;
  private final int clearConfirmCount;
  private final double triggerHysteresisDelta;
  private final double clearHysteresisDelta;
  private final double lowRiskThreshold;
  private final double mediumRiskThreshold;
  private final double highRiskThreshold;
  private final long debounceWindowMs;
  private final boolean evidenceEnabled;
  @NonNull
  private final String evidenceType;
  @NonNull
  private final String evidenceMimeType;
  private final int evidenceJpegQuality;
  private final int evidenceMaxBytes;
  private final long evidenceSequenceWindowMs;
  private final long evidencePostWindowMs;
  private final long evidenceSequenceSampleIntervalMs;
  private final int evidenceSequenceMaxFrames;

  private EdgeRuntimeConfig(@Nullable String configVersion,
                            long temporalWindowSizeMs,
                            int smoothingWindowCount,
                            int stableWindowHitCount,
                            int clearWindowCount,
                            double featureEmaAlpha,
                            long blinkMinDurationMs,
                            long blinkMaxDurationMs,
                            long yawnMinDurationMs,
                            float minSignalConfidence,
                            double fatiguePerclosThreshold,
                            long fatiguePerclosDurationMs,
                            int fatigueYawnCountThreshold,
                            long fatigueYawnWindowMaxMs,
                            long distractionHeadPoseDurationMs,
                            double distractionHeadPoseStabilityThreshold,
                            double distractionHeadDownThreshold,
                            long distractionHeadDownDurationMs,
                            double distractionGazeOffsetThreshold,
                            long distractionGazeOffsetDurationMs,
                            int triggerConfirmCount,
                            int clearConfirmCount,
                            double triggerHysteresisDelta,
                            double clearHysteresisDelta,
                            double lowRiskThreshold,
                            double mediumRiskThreshold,
                            double highRiskThreshold,
                            long debounceWindowMs,
                            boolean evidenceEnabled,
                            @NonNull String evidenceType,
                            @NonNull String evidenceMimeType,
                            int evidenceJpegQuality,
                            int evidenceMaxBytes,
                            long evidenceSequenceWindowMs,
                            long evidencePostWindowMs,
                            long evidenceSequenceSampleIntervalMs,
                            int evidenceSequenceMaxFrames) {
    this.configVersion = configVersion;
    this.temporalWindowSizeMs = temporalWindowSizeMs;
    this.smoothingWindowCount = smoothingWindowCount;
    this.stableWindowHitCount = stableWindowHitCount;
    this.clearWindowCount = clearWindowCount;
    this.featureEmaAlpha = featureEmaAlpha;
    this.blinkMinDurationMs = blinkMinDurationMs;
    this.blinkMaxDurationMs = blinkMaxDurationMs;
    this.yawnMinDurationMs = yawnMinDurationMs;
    this.minSignalConfidence = minSignalConfidence;
    this.fatiguePerclosThreshold = fatiguePerclosThreshold;
    this.fatiguePerclosDurationMs = fatiguePerclosDurationMs;
    this.fatigueYawnCountThreshold = fatigueYawnCountThreshold;
    this.fatigueYawnWindowMaxMs = fatigueYawnWindowMaxMs;
    this.distractionHeadPoseDurationMs = distractionHeadPoseDurationMs;
    this.distractionHeadPoseStabilityThreshold = distractionHeadPoseStabilityThreshold;
    this.distractionHeadDownThreshold = distractionHeadDownThreshold;
    this.distractionHeadDownDurationMs = distractionHeadDownDurationMs;
    this.distractionGazeOffsetThreshold = distractionGazeOffsetThreshold;
    this.distractionGazeOffsetDurationMs = distractionGazeOffsetDurationMs;
    this.triggerConfirmCount = triggerConfirmCount;
    this.clearConfirmCount = clearConfirmCount;
    this.triggerHysteresisDelta = triggerHysteresisDelta;
    this.clearHysteresisDelta = clearHysteresisDelta;
    this.lowRiskThreshold = lowRiskThreshold;
    this.mediumRiskThreshold = mediumRiskThreshold;
    this.highRiskThreshold = highRiskThreshold;
    this.debounceWindowMs = debounceWindowMs;
    this.evidenceEnabled = evidenceEnabled;
    this.evidenceType = evidenceType;
    this.evidenceMimeType = evidenceMimeType;
    this.evidenceJpegQuality = evidenceJpegQuality;
    this.evidenceMaxBytes = evidenceMaxBytes;
    this.evidenceSequenceWindowMs = evidenceSequenceWindowMs;
    this.evidencePostWindowMs = evidencePostWindowMs;
    this.evidenceSequenceSampleIntervalMs = evidenceSequenceSampleIntervalMs;
    this.evidenceSequenceMaxFrames = evidenceSequenceMaxFrames;
  }

  @NonNull
  public static EdgeRuntimeConfig defaults() {
    return fromJsonObject(new JSONObject());
  }

  @NonNull
  public static EdgeRuntimeConfig fromContext(@NonNull EdgeLocalContext context) {
    if (context.runtimeConfigJson == null || context.runtimeConfigJson.trim().isEmpty()) {
      return defaults();
    }
    try {
      return fromJsonObject(new JSONObject(context.runtimeConfigJson));
    } catch (Exception ignored) {
      return defaults();
    }
  }

  @NonNull
  public static EdgeRuntimeConfig fromJsonObject(@NonNull JSONObject root) {
    JSONObject risk = root.optJSONObject("risk");
    JSONObject temporal = root.optJSONObject("temporal");
    JSONObject uploadPolicy = root.optJSONObject("uploadPolicy");
    JSONObject evidencePolicy = root.optJSONObject("evidencePolicy");

    return new EdgeRuntimeConfig(
      text(root, "configVersion"),
      longValue(temporal, "windowSizeMs", DEFAULT_TEMPORAL_WINDOW_MS),
      intValue(temporal, "smoothingWindowCount", DEFAULT_SMOOTHING_WINDOW_COUNT),
      intValue(temporal, "stableWindowHitCount", DEFAULT_STABLE_WINDOW_HIT_COUNT),
      intValue(temporal, "clearWindowCount", DEFAULT_CLEAR_WINDOW_COUNT),
      doubleValue(temporal, "featureEmaAlpha", DEFAULT_FEATURE_EMA_ALPHA),
      longValue(temporal, "blinkMinDurationMs", DEFAULT_BLINK_MIN_DURATION_MS),
      longValue(temporal, "blinkMaxDurationMs", DEFAULT_BLINK_MAX_DURATION_MS),
      longValue(temporal, "yawnMinDurationMs", DEFAULT_YAWN_MIN_DURATION_MS),
      (float) doubleValue(temporal, "minSignalConfidence", DEFAULT_MIN_SIGNAL_CONFIDENCE),
      doubleValue(risk, "fatiguePerclosThreshold", DEFAULT_FATIGUE_PERCLOS_THRESHOLD),
      longValue(risk, "fatiguePerclosDurationMs", DEFAULT_FATIGUE_PERCLOS_DURATION_MS),
      intValue(risk, "fatigueYawnCountThreshold", DEFAULT_FATIGUE_YAWN_COUNT_THRESHOLD),
      longValue(risk, "fatigueYawnWindowMaxMs", DEFAULT_FATIGUE_YAWN_WINDOW_MAX_MS),
      longValue(risk, "distractionHeadPoseDurationMs", DEFAULT_DISTRACTION_HEAD_POSE_DURATION_MS),
      doubleValue(risk, "distractionHeadPoseStabilityThreshold", DEFAULT_DISTRACTION_HEAD_POSE_STABILITY_THRESHOLD),
      doubleValue(risk, "distractionHeadDownThreshold", DEFAULT_DISTRACTION_HEAD_DOWN_THRESHOLD),
      longValue(risk, "distractionHeadDownDurationMs", DEFAULT_DISTRACTION_HEAD_DOWN_DURATION_MS),
      doubleValue(risk, "distractionGazeOffsetThreshold", DEFAULT_DISTRACTION_GAZE_OFFSET_THRESHOLD),
      longValue(risk, "distractionGazeOffsetDurationMs", DEFAULT_DISTRACTION_GAZE_OFFSET_DURATION_MS),
      intValue(risk, "triggerConfirmCount", DEFAULT_TRIGGER_CONFIRM_COUNT),
      intValue(risk, "clearConfirmCount", DEFAULT_CLEAR_CONFIRM_COUNT),
      doubleValue(risk, "triggerHysteresisDelta", DEFAULT_TRIGGER_HYSTERESIS_DELTA),
      doubleValue(risk, "clearHysteresisDelta", DEFAULT_CLEAR_HYSTERESIS_DELTA),
      doubleValue(risk, "lowRiskThreshold", DEFAULT_LOW_RISK_THRESHOLD),
      doubleValue(risk, "mediumRiskThreshold", DEFAULT_MEDIUM_RISK_THRESHOLD),
      doubleValue(risk, "highRiskThreshold", DEFAULT_HIGH_RISK_THRESHOLD),
      longValue(uploadPolicy, "debounceWindowMs", DEFAULT_DEBOUNCE_WINDOW_MS),
      booleanValue(evidencePolicy, "enabled", true),
      firstNonBlank(text(evidencePolicy, "type"), "VIDEO_CLIP"),
      firstNonBlank(text(evidencePolicy, "mimeType"), "video/mp4"),
      intValue(evidencePolicy, "jpegQuality", DEFAULT_EVIDENCE_JPEG_QUALITY),
      intValue(evidencePolicy, "maxBytes", DEFAULT_EVIDENCE_MAX_BYTES),
      longValue(evidencePolicy, "sequenceWindowMs", DEFAULT_EVIDENCE_SEQUENCE_WINDOW_MS),
      longValue(evidencePolicy, "postWindowMs", DEFAULT_EVIDENCE_POST_WINDOW_MS),
      longValue(evidencePolicy, "sequenceSampleIntervalMs", DEFAULT_EVIDENCE_SEQUENCE_SAMPLE_INTERVAL_MS),
      intValue(evidencePolicy, "sequenceMaxFrames", DEFAULT_EVIDENCE_SEQUENCE_MAX_FRAMES)
    );
  }

  @NonNull
  public RiskEngineConfig toRiskEngineConfig() {
    return new RiskEngineConfig(
      new FatigueWeights(),
      new DistractionWeights(),
      fatiguePerclosThreshold,
      fatiguePerclosDurationMs,
      fatigueYawnCountThreshold,
      fatigueYawnWindowMaxMs,
      distractionHeadPoseDurationMs,
      distractionHeadPoseStabilityThreshold,
      distractionHeadDownThreshold,
      distractionHeadDownDurationMs,
      distractionGazeOffsetThreshold,
      distractionGazeOffsetDurationMs,
      triggerConfirmCount,
      clearConfirmCount,
      triggerHysteresisDelta,
      clearHysteresisDelta,
      lowRiskThreshold,
      mediumRiskThreshold,
      highRiskThreshold
    );
  }

  @NonNull
  public TemporalEngineConfig toTemporalEngineConfig() {
    return new TemporalEngineConfig(
      temporalWindowSizeMs,
      smoothingWindowCount,
      stableWindowHitCount,
      clearWindowCount,
      featureEmaAlpha,
      blinkMinDurationMs,
      blinkMaxDurationMs,
      yawnMinDurationMs,
      minSignalConfidence,
      distractionHeadDownThreshold,
      distractionGazeOffsetThreshold,
      distractionHeadPoseStabilityThreshold,
      distractionHeadDownDurationMs,
      distractionGazeOffsetDurationMs
    );
  }

  @Nullable
  public String configVersion() {
    return configVersion;
  }

  public long debounceWindowMs() {
    return debounceWindowMs;
  }

  public boolean evidenceEnabled() {
    return evidenceEnabled;
  }

  @NonNull
  public String evidenceType() {
    return evidenceType;
  }

  @NonNull
  public String evidenceMimeType() {
    return evidenceMimeType;
  }

  public int evidenceJpegQuality() {
    return Math.max(35, Math.min(90, evidenceJpegQuality));
  }

  public int evidenceMaxBytes() {
    return Math.max(8 * 1024, Math.min(20 * 1024 * 1024, evidenceMaxBytes));
  }

  public long evidenceSequenceWindowMs() {
    return Math.max(1_000L, Math.min(30_000L, evidenceSequenceWindowMs));
  }

  public long evidencePostWindowMs() {
    return Math.max(0L, Math.min(10_000L, evidencePostWindowMs));
  }

  public long evidenceSequenceSampleIntervalMs() {
    return Math.max(33L, Math.min(2_000L, evidenceSequenceSampleIntervalMs));
  }

  public int evidenceSequenceMaxFrames() {
    return Math.max(1, Math.min(180, evidenceSequenceMaxFrames));
  }

  @Nullable
  private static String text(@Nullable JSONObject object, @NonNull String key) {
    if (object == null || !object.has(key) || object.isNull(key)) {
      return null;
    }
    String value = object.optString(key, null);
    return value == null || value.trim().isEmpty() ? null : value;
  }

  private static long longValue(@Nullable JSONObject object, @NonNull String key, long fallback) {
    return object == null || !object.has(key) || object.isNull(key) ? fallback : object.optLong(key, fallback);
  }

  private static int intValue(@Nullable JSONObject object, @NonNull String key, int fallback) {
    return object == null || !object.has(key) || object.isNull(key) ? fallback : object.optInt(key, fallback);
  }

  private static double doubleValue(@Nullable JSONObject object, @NonNull String key, double fallback) {
    return object == null || !object.has(key) || object.isNull(key) ? fallback : object.optDouble(key, fallback);
  }

  private static boolean booleanValue(@Nullable JSONObject object, @NonNull String key, boolean fallback) {
    return object == null || !object.has(key) || object.isNull(key) ? fallback : object.optBoolean(key, fallback);
  }

  @NonNull
  private static String firstNonBlank(@Nullable String value, @NonNull String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }
}
