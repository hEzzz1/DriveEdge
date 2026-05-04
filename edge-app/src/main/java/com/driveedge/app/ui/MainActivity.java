package com.driveedge.app.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.driveedge.app.R;
import com.driveedge.app.camera.FrameData;
import com.driveedge.app.edge.EdgeApiClient;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;
import com.driveedge.app.edge.EdgeRuntimeConfig;
import com.driveedge.app.evidence.EvidenceFrameArchiveWriter;
import com.driveedge.app.evidence.EvidenceFrameBuffer;
import com.driveedge.app.evidence.EvidenceMp4Writer;
import com.driveedge.app.event.EdgeEventReporter;
import com.driveedge.app.fatigue.LocalDistractionAnalyzer;
import com.driveedge.app.fatigue.LocalFaceSignalAnalyzer;
import com.driveedge.app.fatigue.LocalFatigueAnalyzer;
import com.driveedge.app.fatigue.LocalOnnxDetector;
import com.driveedge.risk.engine.RiskEngine;
import com.driveedge.risk.engine.RiskEngineConfig;
import com.driveedge.risk.engine.RiskEventCandidate;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.temporal.engine.FeatureWindow;
import com.driveedge.temporal.engine.TemporalEngine;
import com.driveedge.temporal.engine.TemporalEngineConfig;
import com.driveedge.infer.yolo.DetectionResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends AppCompatActivity {
  private static final String TAG = "DriveEdgeMain";
  private static final long PREVIEW_UPLOAD_TICK_MS = 200L;
  private static final long QUICK_FATIGUE_UI_HOLD_MS = 2_000L;
  private static final long QUICK_FATIGUE_EVENT_CONFIRM_MS = 1_500L;
  private static final long QUICK_FATIGUE_EVENT_COOLDOWN_MS = 8_000L;
  private static final long WAITING_STATUS_INTERVAL_MS = 1000L;
  private static final int ANALYSIS_TARGET_WIDTH = 1920;
  private static final int ANALYSIS_TARGET_HEIGHT = 1080;
  private static final int AE_AWB_STABLE_FRAME_COUNT = 8;
  private static final int DEFAULT_PREVIEW_WIDTH = 1280;
  private static final int DEFAULT_PREVIEW_HEIGHT = 720;
  private static final String LOCAL_MODEL_ASSET_PATH = "models/yolov8face.onnx";
  private static final String LOCAL_FATIGUE_MODEL_ASSET_PATH = "models/face_landmarker.task";
  private static final float LOCAL_CONF_THRESHOLD = 0.25f;
  private static final float LOCAL_NMS_THRESHOLD = 0.45f;
  private static final float FACE_CROP_MARGIN_RATIO = 0.18f;
  private static final int FATIGUE_MISSING_FACE_TOLERANCE_FRAMES = 2;
  private static final int RISK_MISSING_FACE_TOLERANCE_FRAMES = 2;
  private static final String EVIDENCE_TYPE_KEY_FRAME = "KEY_FRAME";
  private static final String EVIDENCE_TYPE_VIDEO_CLIP = "VIDEO_CLIP";
  private static final String EVIDENCE_TYPE_FRAME_SEQUENCE = "FRAME_SEQUENCE";
  private static final String EVIDENCE_MIME_IMAGE_JPEG = "image/jpeg";
  private static final String EVIDENCE_MIME_VIDEO_MP4 = "video/mp4";
  private static final String EVIDENCE_MIME_FRAME_SEQUENCE = "application/zip";
  private static final long EVIDENCE_ARCHIVE_CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;
  private static final long EVIDENCE_ARCHIVE_LOCAL_RETENTION_MS = 2 * 24 * 60 * 60 * 1000L;
  @NonNull
  private static final TemporalEngineConfig REALTIME_TEMPORAL_CONFIG =
    new TemporalEngineConfig(
      4_000L,
      3,
      2,
      2,
      0.65,
      80L,
      600L,
      700L,
      0.60f,
      0.62,
      0.68,
      0.72,
      2_500L,
      2_500L
    );
  @NonNull
  private static final RiskEngineConfig REALTIME_RISK_CONFIG =
    new RiskEngineConfig(
      new com.driveedge.risk.engine.FatigueWeights(),
      new com.driveedge.risk.engine.DistractionWeights(),
      0.24,
      2_000L,
      1,
      30_000L,
      3_500L,
      0.72,
      0.62,
      2_500L,
      0.68,
      2_500L,
      2,
      2,
      0.03,
      0.08,
      0.48,
      0.65,
      0.80
  );
  private static final byte[] EMPTY_FRAME_BYTES = new byte[0];

  private TextureView previewView;
  private TextView statusView;
  private TextView fatigueStatusView;
  private TextView edgeContextView;
  private Button startButton;
  private Button stopButton;
  private Button syncButton;
  private Button signOutButton;

  @Nullable
  private CameraManager cameraManager;
  @Nullable
  private String openedCameraId;
  private int frontCameraSensorOrientation = 0;
  private int frontCameraLensFacing = CameraCharacteristics.LENS_FACING_FRONT;
  private int previewWidth = DEFAULT_PREVIEW_WIDTH;
  private int previewHeight = DEFAULT_PREVIEW_HEIGHT;
  private int analysisWidth = ANALYSIS_TARGET_WIDTH;
  private int analysisHeight = ANALYSIS_TARGET_HEIGHT;
  private int noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_FAST;
  private int edgeMode = CaptureRequest.EDGE_MODE_FAST;
  private int toneMapMode = CaptureRequest.TONEMAP_MODE_FAST;
  private boolean aeLockAvailable = false;
  private boolean awbLockAvailable = false;
  private boolean aeAwbLocked = false;
  private int aeAwbStableFrames = 0;
  @Nullable
  private CaptureRequest.Builder previewRequestBuilder;

  @Nullable
  private HandlerThread cameraThread;
  @Nullable
  private Handler cameraHandler;
  @Nullable
  private CameraDevice cameraDevice;
  @Nullable
  private CameraCaptureSession captureSession;
  @Nullable
  private ImageReader imageReader;

  private final ExecutorService inferExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean inferTaskRunning = new AtomicBoolean(false);
  private final AtomicInteger secondFrameCounter = new AtomicInteger(0);

  @Nullable
  private LocalOnnxDetector localOnnxDetector;
  @Nullable
  private LocalFaceSignalAnalyzer localFaceSignalAnalyzer;
  @Nullable
  private LocalFatigueAnalyzer localFatigueAnalyzer;
  @NonNull
  private final Object localOnnxDetectorLock = new Object();
  @NonNull
  private final Object localFaceSignalAnalyzerLock = new Object();
  @NonNull
  private final Object localFatigueAnalyzerLock = new Object();
  @NonNull
  private final LocalDistractionAnalyzer localDistractionAnalyzer = new LocalDistractionAnalyzer();

  private boolean captureStarted = false;
  private boolean previewUploadLoopRunning = false;
  private long lastStatusUpdateMs = 0L;
  private long lastInferStatusUpdateMs = 0L;
  private long lastWaitingStatusMs = 0L;
  private long lastFatigueWarningToastMs = 0L;
  private long lastDistractionWarningToastMs = 0L;
  private long quickFatigueCandidateStartedElapsedMs = 0L;
  private long lastQuickFatigueEventReportedElapsedMs = 0L;
  private long lastEdgeEventScheduledElapsedMs = 0L;
  private int realtimeRiskMissingFaceFrames = 0;
  private boolean analyzersReady = false;
  private long lastQuickFatigueDetectedElapsedMs = 0L;
  @Nullable
  private ToneGenerator fatigueToneGenerator;
  @Nullable
  private EdgeEventReporter edgeEventReporter;
  @Nullable
  private EdgeContextStore edgeContextStore;
  @NonNull
  private final EdgeApiClient edgeApiClient = new EdgeApiClient();
  @NonNull
  private final EdgeFlowController edgeFlowController = new EdgeFlowController(edgeApiClient);
  @NonNull
  private volatile EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();
  @NonNull
  private volatile EdgeRuntimeConfig edgeRuntimeConfig = EdgeRuntimeConfig.defaults();
  @Nullable
  private volatile String appliedRuntimeConfigVersion;
  @NonNull
  private volatile String edgeEventStatusLine = "事件上报：待触发";
  @NonNull
  private volatile TemporalEngine temporalEngine = new TemporalEngine(REALTIME_TEMPORAL_CONFIG);
  @NonNull
  private volatile RiskEngine riskEngine = new RiskEngine(REALTIME_RISK_CONFIG);
  @Nullable
  private volatile RiskEventCandidate latestRiskCandidate;
  @NonNull
  private final Object latestYuvFrameLock = new Object();
  @Nullable
  private YuvFrame latestYuvFrame;
  @NonNull
  private final EvidenceFrameBuffer evidenceFrameBuffer = new EvidenceFrameBuffer();
  @NonNull
  private final EvidenceFrameArchiveWriter evidenceFrameArchiveWriter = new EvidenceFrameArchiveWriter();
  @NonNull
  private final EvidenceMp4Writer evidenceMp4Writer = new EvidenceMp4Writer();
  @Nullable
  private PendingEdgeReport pendingEdgeReport;
  private long lastEvidenceArchiveCleanupMs = 0L;

  private final ActivityResultLauncher<String> cameraPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      if (!granted) {
        Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_SHORT).show();
        return;
      }
      startCaptureInternal();
    });

  private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
      if (captureStarted) {
        openCameraIfReady();
      }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
      // no-op
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
      closeCameraResources();
      return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
      // no-op
    }
  };

  private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
    @Override
    public void onCaptureCompleted(
      @NonNull CameraCaptureSession session,
      @NonNull CaptureRequest request,
      @NonNull TotalCaptureResult result
    ) {
      maybeLockAeAwb(session, result);
    }
  };

  private final Runnable previewUploadRunnable = this::uploadPreviewFrameTick;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    previewView = findViewById(R.id.previewView);
    statusView = findViewById(R.id.statusView);
    fatigueStatusView = findViewById(R.id.fatigueStatusView);
    edgeContextView = findViewById(R.id.edgeContextView);
    startButton = findViewById(R.id.startButton);
    stopButton = findViewById(R.id.stopButton);
    syncButton = findViewById(R.id.syncButton);
    signOutButton = findViewById(R.id.signOutButton);

    cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = edgeContextStore.load();
    applyRuntimeConfig(edgeLocalContext);
    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, MainActivity.class)) {
      return;
    }

    previewView.setSurfaceTextureListener(surfaceTextureListener);
    startButton.setOnClickListener(v -> ensurePermissionThenStart());
    stopButton.setOnClickListener(v -> stopCapture());
    syncButton.setOnClickListener(v -> refreshEdgeContext(false));
    signOutButton.setOnClickListener(v -> signOutDriver());

    startButton.setEnabled(false);
    stopButton.setEnabled(false);
    signOutButton.setEnabled(edgeLocalContext.hasActiveSession());
    resetInferSession();
    edgeEventReporter =
      new EdgeEventReporter(
        getApplicationContext(),
        statusLine -> runOnUiThread(() -> {
          edgeEventStatusLine = statusLine;
          renderEdgeContext();
        })
      );
    statusView.setText(getString(R.string.status_idle));
    renderEdgeContext();
    preloadLocalAnalyzers();
    refreshEdgeContext(true);
  }

  @Override
  protected void onDestroy() {
    stopCapture();
    releaseLocalDetector();
    releaseLocalFaceSignalAnalyzer();
    releaseLocalFatigueAnalyzer();
    releaseFatigueToneGenerator();
    releaseEdgeEventReporter();
    inferExecutor.shutdownNow();
    edgeIoExecutor.shutdownNow();
    super.onDestroy();
  }

  private void ensurePermissionThenStart() {
    if (!edgeLocalContext.hasActiveSession()) {
      Toast.makeText(this, R.string.edge_session_required, Toast.LENGTH_SHORT).show();
      navigateToSignIn();
      return;
    }
    if (!analyzersReady) {
      fatigueStatusView.setText(getString(R.string.status_local_boot));
      return;
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCaptureInternal();
      return;
    }
    cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
  }

  private void startCaptureInternal() {
    if (captureStarted) {
      return;
    }
    captureStarted = true;
    resetRealtimeRiskTracking();
    resetInferSession();
    evidenceFrameBuffer.clear();
    statusView.setText(getString(R.string.status_connected));
    startButton.setEnabled(false);
    stopButton.setEnabled(true);
    startCameraThreadIfNeeded();
    startPreviewUploadLoop();
    if (previewView.isAvailable()) {
      openCameraIfReady();
    }
  }

  private void preloadLocalAnalyzers() {
    fatigueStatusView.setText(getString(R.string.status_local_boot));
    inferExecutor.execute(() -> {
      try {
        getOrCreateLocalFaceSignalAnalyzer();
        getOrCreateLocalFatigueAnalyzer();
        analyzersReady = true;
        runOnUiThread(() -> {
          updateSessionControls();
          updateInferIdleStatus();
        });
      } catch (Throwable error) {
        analyzersReady = false;
        Log.e(TAG, "Analyzer preload failed", error);
        runOnUiThread(() -> {
          startButton.setEnabled(false);
          updateSessionControls();
          fatigueStatusView.setText(getString(R.string.status_local_error));
        });
      }
    });
  }

  private void refreshEdgeContext(boolean silentFailure) {
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext updated = edgeFlowController.syncContext(store.load());
        store.save(updated);
        edgeLocalContext = updated;
        applyRuntimeConfig(updated);
        runOnUiThread(() -> {
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, MainActivity.class)) {
            return;
          }
          renderEdgeContext();
        });
      } catch (Exception error) {
        if (!silentFailure) {
          runOnUiThread(() -> Toast.makeText(
            this,
            getString(R.string.edge_sync_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)),
            Toast.LENGTH_SHORT
          ).show());
        }
        runOnUiThread(this::renderEdgeContext);
      }
    });
  }

  private void signOutDriver() {
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext updated = edgeApiClient.signOut(store.load(), "MANUAL_SIGN_OUT");
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          if (captureStarted) {
            stopCapture();
          }
          navigateToSignIn();
        });
      } catch (Exception error) {
        runOnUiThread(() -> Toast.makeText(
          this,
          getString(R.string.edge_sign_out_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)),
          Toast.LENGTH_SHORT
        ).show());
      }
    });
  }

  private void renderEdgeContext() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = store.load();
      applyRuntimeConfig(edgeLocalContext);
    }
    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, MainActivity.class)) {
      return;
    }
    EdgeLocalContext context = edgeLocalContext;
    edgeContextView.setText(getString(
      R.string.edge_context_format,
      EdgeFlowController.displayValue(context.vehiclePlateNumber, context.vehicleId),
      EdgeFlowController.displayDriverValue(context),
      edgeFlowController.displayBindStatus(context),
      edgeEventStatusLine
    ));
    updateSessionControls();
  }

  private void applyRuntimeConfig(@NonNull EdgeLocalContext context) {
    EdgeRuntimeConfig nextConfig = EdgeRuntimeConfig.fromContext(context);
    String nextKey = String.valueOf(nextConfig.configVersion()) + ":" + (context.runtimeConfigJson == null ? 0 : context.runtimeConfigJson.hashCode());
    if (nextKey.equals(appliedRuntimeConfigVersion)) {
      edgeRuntimeConfig = nextConfig;
      return;
    }
    try {
      TemporalEngine nextTemporalEngine = new TemporalEngine(nextConfig.toTemporalEngineConfig());
      RiskEngine nextRiskEngine = new RiskEngine(nextConfig.toRiskEngineConfig());
      temporalEngine = nextTemporalEngine;
      riskEngine = nextRiskEngine;
      edgeRuntimeConfig = nextConfig;
      appliedRuntimeConfigVersion = nextKey;
      latestRiskCandidate = null;
      realtimeRiskMissingFaceFrames = 0;
    } catch (Exception error) {
      Log.w(TAG, "Runtime edge config ignored", error);
    }
  }

  private void updateSessionControls() {
    boolean sessionReady = edgeLocalContext.hasActiveSession();
    startButton.setEnabled(!captureStarted && analyzersReady && sessionReady);
    signOutButton.setEnabled(sessionReady);
    syncButton.setEnabled(true);
  }

  private void navigateToSignIn() {
    Intent intent = new Intent(this, SignInActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
    finish();
  }

  @NonNull
  private String formatEdgeError(@NonNull Exception error) {
    return edgeFlowController.formatEdgeError(edgeLocalContext, error);
  }

  private void stopCapture() {
    if (!captureStarted) {
      return;
    }
    captureStarted = false;
    closeCameraResources();
    stopCameraThreadIfNeeded();
    stopPreviewUploadLoop();
    secondFrameCounter.set(0);
    resetRealtimeRiskTracking();
    statusView.setText(getString(R.string.status_stopped));
    fatigueStatusView.setText(getString(R.string.status_local_stopped));
    updateSessionControls();
    stopButton.setEnabled(false);
  }

  private void startCameraThreadIfNeeded() {
    if (cameraThread != null) {
      return;
    }
    cameraThread = new HandlerThread("camera2-yuv-thread");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
  }

  private void stopCameraThreadIfNeeded() {
    HandlerThread thread = cameraThread;
    cameraThread = null;
    cameraHandler = null;
    if (thread == null) {
      return;
    }
    thread.quitSafely();
    try {
      thread.join(1500L);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressLint("MissingPermission")
  private void openCameraIfReady() {
    if (!captureStarted || cameraDevice != null) {
      return;
    }
    CameraManager manager = cameraManager;
    Handler handler = cameraHandler;
    if (manager == null || handler == null) {
      return;
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return;
    }

    try {
      String cameraId = resolveFrontCameraId(manager);
      manager.openCamera(cameraId, cameraStateCallback, handler);
    } catch (Exception error) {
      postStatusText(getString(R.string.status_error));
    }
  }

  @NonNull
  private String resolveFrontCameraId(@NonNull CameraManager manager) throws CameraAccessException {
    String[] cameraIds = manager.getCameraIdList();
    if (cameraIds.length == 0) {
      throw new IllegalStateException("No camera device");
    }

    for (String cameraId : cameraIds) {
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
      Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
      if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        frontCameraSensorOrientation = sensorOrientation == null ? 0 : sensorOrientation;
        frontCameraLensFacing = lensFacing;
        openedCameraId = cameraId;
        updatePreviewSize(characteristics);
        updateAnalysisConfig(characteristics);
        updateQualityModeConfig(characteristics);
        return cameraId;
      }
    }

    String fallback = cameraIds[0];
    CameraCharacteristics characteristics = manager.getCameraCharacteristics(fallback);
    Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
    frontCameraSensorOrientation = sensorOrientation == null ? 0 : sensorOrientation;
    frontCameraLensFacing = lensFacing == null ? CameraCharacteristics.LENS_FACING_FRONT : lensFacing;
    openedCameraId = fallback;
    updatePreviewSize(characteristics);
    updateAnalysisConfig(characteristics);
    updateQualityModeConfig(characteristics);
    return fallback;
  }

  private void updatePreviewSize(@NonNull CameraCharacteristics characteristics) {
    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size selected = choosePreviewSize(
      map == null ? null : map.getOutputSizes(SurfaceTexture.class),
      DEFAULT_PREVIEW_WIDTH,
      DEFAULT_PREVIEW_HEIGHT
    );
    previewWidth = selected.getWidth();
    previewHeight = selected.getHeight();
  }

  private void updateAnalysisConfig(@NonNull CameraCharacteristics characteristics) {
    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size selected = chooseAnalysisSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
    analysisWidth = selected.getWidth();
    analysisHeight = selected.getHeight();
  }

  private void updateQualityModeConfig(@NonNull CameraCharacteristics characteristics) {
    int[] noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
    int[] edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
    int[] toneMapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
    Boolean aeLock = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
    Boolean awbLock = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);

    noiseReductionMode = choosePreferredMode(
      noiseModes,
      CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
      CaptureRequest.NOISE_REDUCTION_MODE_FAST
    );
    edgeMode = choosePreferredMode(
      edgeModes,
      CaptureRequest.EDGE_MODE_HIGH_QUALITY,
      CaptureRequest.EDGE_MODE_FAST
    );
    toneMapMode = choosePreferredMode(
      toneMapModes,
      CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
      CaptureRequest.TONEMAP_MODE_FAST
    );
    aeLockAvailable = aeLock != null && aeLock;
    awbLockAvailable = awbLock != null && awbLock;
  }

  @NonNull
  private Size choosePreviewSize(@Nullable Size[] sizes, int targetW, int targetH) {
    if (sizes == null || sizes.length == 0) {
      return new Size(targetW, targetH);
    }
    long targetRatioNum = targetW;
    long targetRatioDen = targetH;

    Size best = null;
    for (Size size : sizes) {
      long w = size.getWidth();
      long h = size.getHeight();
      if (w * targetRatioDen != h * targetRatioNum) {
        continue;
      }
      if (best == null || (w * h) > ((long) best.getWidth() * best.getHeight())) {
        best = size;
      }
    }
    if (best != null) {
      return best;
    }

    Size fallback = sizes[0];
    for (Size size : sizes) {
      if ((long) size.getWidth() * size.getHeight() > (long) fallback.getWidth() * fallback.getHeight()) {
        fallback = size;
      }
    }
    return fallback;
  }

  @NonNull
  private Size chooseAnalysisSize(@Nullable Size[] sizes) {
    if (sizes == null || sizes.length == 0) {
      return new Size(ANALYSIS_TARGET_WIDTH, ANALYSIS_TARGET_HEIGHT);
    }

    for (Size size : sizes) {
      if (size.getWidth() == ANALYSIS_TARGET_WIDTH && size.getHeight() == ANALYSIS_TARGET_HEIGHT) {
        return size;
      }
    }
    for (Size size : sizes) {
      if (size.getWidth() == 1920 && size.getHeight() == 1080) {
        return size;
      }
    }

    Size minAboveTarget = null;
    for (Size size : sizes) {
      if (size.getWidth() < ANALYSIS_TARGET_WIDTH || size.getHeight() < ANALYSIS_TARGET_HEIGHT) {
        continue;
      }
      if (size.getWidth() * 9 != size.getHeight() * 16) {
        continue;
      }
      if (minAboveTarget == null || (size.getWidth() * size.getHeight()) < (minAboveTarget.getWidth() * minAboveTarget.getHeight())) {
        minAboveTarget = size;
      }
    }
    if (minAboveTarget != null) {
      return minAboveTarget;
    }

    Size best169 = null;
    for (Size size : sizes) {
      if (size.getWidth() * 9 != size.getHeight() * 16) {
        continue;
      }
      if (best169 == null || (size.getWidth() * size.getHeight()) > (best169.getWidth() * best169.getHeight())) {
        best169 = size;
      }
    }
    if (best169 != null) {
      return best169;
    }

    Size largest = sizes[0];
    for (Size size : sizes) {
      if ((size.getWidth() * size.getHeight()) > (largest.getWidth() * largest.getHeight())) {
        largest = size;
      }
    }
    return largest;
  }

  private int choosePreferredMode(@Nullable int[] modes, int preferred, int fallback) {
    if (modes == null || modes.length == 0) {
      return fallback;
    }
    for (int mode : modes) {
      if (mode == preferred) {
        return preferred;
      }
    }
    for (int mode : modes) {
      if (mode == fallback) {
        return fallback;
      }
    }
    return modes[0];
  }

  private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
    @Override
    public void onOpened(@NonNull CameraDevice camera) {
      cameraDevice = camera;
      createCaptureSession(false, false);
    }

    @Override
    public void onDisconnected(@NonNull CameraDevice camera) {
      camera.close();
      cameraDevice = null;
    }

    @Override
    public void onError(@NonNull CameraDevice camera, int error) {
      camera.close();
      cameraDevice = null;
      runOnUiThread(() -> statusView.setText(getString(R.string.status_camera_failed)));
    }
  };

  private void createCaptureSession(boolean withRecorder, boolean startRecorderOnConfigured) {
    CameraDevice device = cameraDevice;
    Handler handler = cameraHandler;
    if (device == null || handler == null || !previewView.isAvailable()) {
      return;
    }

    try {
      ImageReader reader = imageReader;
      if (reader == null) {
        reader = ImageReader.newInstance(analysisWidth, analysisHeight, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, handler);
        imageReader = reader;
      }

      SurfaceTexture surfaceTexture = previewView.getSurfaceTexture();
      if (surfaceTexture == null) {
        return;
      }
      surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);
      Surface previewSurface = new Surface(surfaceTexture);
      Surface yuvSurface = reader == null ? null : reader.getSurface();

      List<Surface> surfaces = new ArrayList<>();
      surfaces.add(previewSurface);
      // Evidence video is assembled from buffered JPEG frames. Keeping the default
      // session to preview + YUV avoids green output on Camera2 implementations
      // that do not handle preview + analysis + encoder surfaces reliably.
      if (yuvSurface != null) {
        surfaces.add(yuvSurface);
      }

      CameraCaptureSession oldSession = captureSession;
      captureSession = null;
      if (oldSession != null) {
        try {
          oldSession.stopRepeating();
        } catch (Exception ignored) {
        }
        oldSession.close();
      }

      device.createCaptureSession(
        surfaces,
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            if (cameraDevice == null) {
              session.close();
              return;
            }
            captureSession = session;
            try {
              CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
              builder.addTarget(previewSurface);
              if (yuvSurface != null) {
                builder.addTarget(yuvSurface);
              }
              applyHighQualityCaptureParams(builder);
              previewRequestBuilder = builder;
              aeAwbStableFrames = 0;
              aeAwbLocked = !aeLockAvailable && !awbLockAvailable;
              session.setRepeatingRequest(
                builder.build(),
                previewCaptureCallback,
                handler
              );
            } catch (Exception error) {
              runOnUiThread(() -> statusView.setText(getString(R.string.status_error)));
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            runOnUiThread(() -> statusView.setText(getString(R.string.status_session_failed)));
          }
        },
        handler
      );
    } catch (Exception error) {
      postStatusText(getString(R.string.status_error));
    }
  }

  private void postStatusText(@NonNull String text) {
    runOnUiThread(() -> statusView.setText(text));
  }

  private void applyHighQualityCaptureParams(@NonNull CaptureRequest.Builder builder) {
    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));

    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
    builder.set(CaptureRequest.EDGE_MODE, edgeMode);
    builder.set(CaptureRequest.TONEMAP_MODE, toneMapMode);

    if (aeLockAvailable) {
      builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
    }
    if (awbLockAvailable) {
      builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
    }
  }

  private void maybeLockAeAwb(@NonNull CameraCaptureSession session, @NonNull CaptureResult result) {
    CaptureRequest.Builder builder = previewRequestBuilder;
    Handler handler = cameraHandler;
    if (builder == null || handler == null || aeAwbLocked) {
      return;
    }

    boolean aeStable = !aeLockAvailable || isAeStable(result.get(CaptureResult.CONTROL_AE_STATE));
    boolean awbStable = !awbLockAvailable || isAwbStable(result.get(CaptureResult.CONTROL_AWB_STATE));
    if (aeStable && awbStable) {
      aeAwbStableFrames++;
    } else {
      aeAwbStableFrames = 0;
    }
    if (aeAwbStableFrames < AE_AWB_STABLE_FRAME_COUNT) {
      return;
    }

    try {
      if (aeLockAvailable) {
        builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
      }
      if (awbLockAvailable) {
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
      }
      session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler);
      aeAwbLocked = true;
    } catch (Exception error) {
      Log.w(TAG, "Failed to lock AE/AWB", error);
    }
  }

  private boolean isAeStable(@Nullable Integer state) {
    if (state == null) {
      return false;
    }
    return state == CaptureResult.CONTROL_AE_STATE_CONVERGED
      || state == CaptureResult.CONTROL_AE_STATE_LOCKED
      || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
  }

  private boolean isAwbStable(@Nullable Integer state) {
    if (state == null) {
      return false;
    }
    return state == CaptureResult.CONTROL_AWB_STATE_CONVERGED
      || state == CaptureResult.CONTROL_AWB_STATE_LOCKED;
  }

  private void onImageAvailable(@NonNull ImageReader reader) {
    Image image = reader.acquireLatestImage();
    if (image == null) {
      return;
    }
    try {
      YuvFrame frame = convertImageToYuvFrame(image);
      synchronized (latestYuvFrameLock) {
        latestYuvFrame = frame;
      }
    } catch (Exception error) {
      Log.w(TAG, "onImageAvailable convert failed", error);
    } finally {
      image.close();
    }
  }

  private void startPreviewUploadLoop() {
    if (previewUploadLoopRunning) {
      return;
    }
    previewUploadLoopRunning = true;
    previewView.removeCallbacks(previewUploadRunnable);
    previewView.post(previewUploadRunnable);
  }

  private void stopPreviewUploadLoop() {
    previewUploadLoopRunning = false;
    previewView.removeCallbacks(previewUploadRunnable);
  }

  private void uploadPreviewFrameTick() {
    if (!previewUploadLoopRunning) {
      return;
    }
    try {
      if (captureStarted && tryReserveInferSlot()) {
        LocalUploadFrame localFrame = convertYuvToLocalUploadFrame();
        if (localFrame != null) {
          dispatchLocalInferFrame(localFrame);
          onFrameStatusTick(localFrame.frame);
        } else {
          maybeUpdateWaitingStatus();
          inferTaskRunning.set(false);
        }
      }
    } catch (Exception ignored) {
      inferTaskRunning.set(false);
    } finally {
      if (previewUploadLoopRunning) {
        previewView.postDelayed(previewUploadRunnable, PREVIEW_UPLOAD_TICK_MS);
      }
    }
  }

  @Nullable
  private LocalUploadFrame convertYuvToLocalUploadFrame() {
    YuvFrame yuvFrame = getLatestYuvFrame();
    if (yuvFrame == null) {
      return null;
    }

    try {
      Bitmap modelBitmap = createModelBitmapFromYuvFrame(yuvFrame);
      FrameData frame = new FrameData(
        modelBitmap.getWidth(),
        modelBitmap.getHeight(),
        0,
        yuvFrame.timestampNs,
        EMPTY_FRAME_BYTES
      );
      return new LocalUploadFrame(
        frame,
        modelBitmap,
        yuvFrame.capturedAtMs
      );
    } catch (Exception error) {
      Log.w(TAG, "Local frame conversion failed", error);
      return null;
    }
  }

  @Nullable
  private YuvFrame getLatestYuvFrame() {
    synchronized (latestYuvFrameLock) {
      return latestYuvFrame;
    }
  }

  @NonNull
  private YuvFrame convertImageToYuvFrame(@NonNull Image image) {
    int width = image.getWidth();
    int height = image.getHeight();
    byte[] nv21 = yuv420888ToNv21(image, width, height);
    long timestampNs = image.getTimestamp() > 0L ? image.getTimestamp() : System.nanoTime();
    return new YuvFrame(width, height, timestampNs, System.currentTimeMillis(), nv21);
  }

  @NonNull
  private byte[] yuv420888ToNv21(@NonNull Image image, int width, int height) {
    Image.Plane[] planes = image.getPlanes();
    ByteBuffer yBuffer = planes[0].getBuffer();
    ByteBuffer uBuffer = planes[1].getBuffer();
    ByteBuffer vBuffer = planes[2].getBuffer();
    int yRowStride = planes[0].getRowStride();
    int yPixelStride = planes[0].getPixelStride();
    int uRowStride = planes[1].getRowStride();
    int uPixelStride = planes[1].getPixelStride();
    int vRowStride = planes[2].getRowStride();
    int vPixelStride = planes[2].getPixelStride();

    byte[] nv21 = new byte[(width * height * 3) / 2];
    int offset = 0;
    for (int row = 0; row < height; row++) {
      int yRowStart = row * yRowStride;
      for (int col = 0; col < width; col++) {
        nv21[offset++] = yBuffer.get(yRowStart + (col * yPixelStride));
      }
    }

    int chromaHeight = height / 2;
    int chromaWidth = width / 2;
    for (int row = 0; row < chromaHeight; row++) {
      int uRowStart = row * uRowStride;
      int vRowStart = row * vRowStride;
      for (int col = 0; col < chromaWidth; col++) {
        nv21[offset++] = vBuffer.get(vRowStart + (col * vPixelStride));
        nv21[offset++] = uBuffer.get(uRowStart + (col * uPixelStride));
      }
    }
    return nv21;
  }

  @NonNull
  private Bitmap createModelBitmapFromYuvFrame(@NonNull YuvFrame frame) {
    Bitmap bitmap = nv21ToBitmap(frame.nv21, frame.width, frame.height);
    int rotationDegrees = computeStillFrameRotationDegrees();
    if (rotationDegrees == 0) {
      return bitmap;
    }
    Bitmap rotated = rotateBitmap(bitmap, rotationDegrees);
    if (rotated != bitmap) {
      bitmap.recycle();
    }
    return rotated;
  }

  @NonNull
  private Bitmap nv21ToBitmap(@NonNull byte[] nv21, int width, int height) {
    int frameSize = width * height;
    int[] argb = new int[frameSize];
    for (int y = 0; y < height; y++) {
      int yRow = y * width;
      int uvRow = frameSize + ((y >> 1) * width);
      for (int x = 0; x < width; x++) {
        int yValue = nv21[yRow + x] & 0xFF;
        int uvIndex = uvRow + (x & ~1);
        int vValue = (nv21[uvIndex] & 0xFF) - 128;
        int uValue = (nv21[uvIndex + 1] & 0xFF) - 128;
        int c = Math.max(0, yValue - 16);
        int r = clampColor((298 * c + (409 * vValue) + 128) >> 8);
        int g = clampColor((298 * c - (100 * uValue) - (208 * vValue) + 128) >> 8);
        int b = clampColor((298 * c + (516 * uValue) + 128) >> 8);
        argb[yRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
      }
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
  }

  private int clampColor(int value) {
    if (value < 0) {
      return 0;
    }
    if (value > 255) {
      return 255;
    }
    return value;
  }

  @NonNull
  private Bitmap rotateBitmap(@NonNull Bitmap source, int rotationDegrees) {
    if (rotationDegrees == 0) {
      return source;
    }
    Matrix matrix = new Matrix();
    matrix.postRotate(rotationDegrees);
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  @NonNull
  private byte[] encodeNv21ToJpegFallback(@NonNull byte[] nv21, int width, int height, int jpegQuality) {
    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(width * height);
    boolean ok = yuvImage.compressToJpeg(new Rect(0, 0, width, height), jpegQuality, outputStream);
    if (!ok) {
      throw new IllegalStateException("Failed to encode NV21 jpeg fallback");
    }
    return outputStream.toByteArray();
  }

  @NonNull
  private EncodedJpeg encodeNormalizedNv21ToJpeg(@NonNull YuvFrame frame, int jpegQuality) {
    byte[] jpeg = encodeNv21ToJpegFallback(frame.nv21, frame.width, frame.height, jpegQuality);
    int rotationDegrees = computeStillFrameRotationDegrees();
    if (rotationDegrees == 0) {
      return new EncodedJpeg(jpeg, frame.width, frame.height);
    }
    byte[] rotatedJpeg = rotateJpeg(jpeg, rotationDegrees, jpegQuality);
    boolean swapSize = rotationDegrees == 90 || rotationDegrees == 270;
    int outWidth = swapSize ? frame.height : frame.width;
    int outHeight = swapSize ? frame.width : frame.height;
    return new EncodedJpeg(rotatedJpeg, outWidth, outHeight);
  }

  @NonNull
  private byte[] rotateJpeg(@NonNull byte[] jpeg, int rotationDegrees, int jpegQuality) {
    Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    if (src == null) {
      throw new IllegalStateException("Failed to decode JPEG for rotation");
    }
    Bitmap rotated = src;
    try {
      if (rotationDegrees != 0) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
      }
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(jpeg.length);
      if (!rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)) {
        throw new IllegalStateException("Failed to encode rotated JPEG");
      }
      return outputStream.toByteArray();
    } finally {
      if (rotated != src) {
        rotated.recycle();
      }
      src.recycle();
    }
  }

  private int computeStillFrameRotationDegrees() {
    int displayDegrees = getDisplayRotationDegrees();
    if (frontCameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
      return (frontCameraSensorOrientation + displayDegrees) % 360;
    }
    return (frontCameraSensorOrientation - displayDegrees + 360) % 360;
  }

  private int getDisplayRotationDegrees() {
    int displayRotation;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      displayRotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
    } else {
      displayRotation = getWindowManager().getDefaultDisplay().getRotation();
    }
    switch (displayRotation) {
      case Surface.ROTATION_90:
        return 90;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_0:
      default:
        return 0;
    }
  }

  private void onFrameStatusTick(@NonNull FrameData frame) {
    secondFrameCounter.incrementAndGet();
    long now = SystemClock.elapsedRealtime();
    if (now - lastStatusUpdateMs < 1000L) {
      return;
    }

    int fps = secondFrameCounter.getAndSet(0);
    lastStatusUpdateMs = now;
    runOnUiThread(() -> statusView.setText(getString(R.string.status_running)));
  }

  private boolean tryReserveInferSlot() {
    return inferTaskRunning.compareAndSet(false, true);
  }

  private void dispatchLocalInferFrame(@NonNull LocalUploadFrame uploadFrame) {
    Bitmap modelBitmap = uploadFrame.modelBitmap;

    inferExecutor.execute(() -> {
      Bitmap analyzerBitmap = modelBitmap;
      try {
        LocalOnnxDetector detector = getOrCreateLocalDetector();
        LocalOnnxDetector.Result result = detector.inferBitmap(modelBitmap);
        analyzerBitmap = cropBestFaceBitmap(modelBitmap, result);
        LocalFaceSignalAnalyzer faceSignalAnalyzer = getOrCreateLocalFaceSignalAnalyzer();
        LocalFaceSignalAnalyzer.Result faceSignals = faceSignalAnalyzer.analyzeBitmap(analyzerBitmap);
        if (faceSignals.faces <= 0 && analyzerBitmap != modelBitmap) {
          analyzerBitmap.recycle();
          analyzerBitmap = modelBitmap;
          faceSignals = faceSignalAnalyzer.analyzeBitmap(modelBitmap);
        }
        LocalFatigueAnalyzer.Result fatigue = getOrCreateLocalFatigueAnalyzer().analyze(faceSignals);
        updateQuickFatigueUiState(fatigue);
        RiskEventCandidate riskCandidate = updateRealtimeRisk(faceSignals, fatigue, uploadFrame.frame.timestampNs / 1_000_000L);
        rememberEvidenceFrame(uploadFrame);
        safeReportEdgeEvent(uploadFrame, fatigue, riskCandidate);

        long finishedAt = SystemClock.elapsedRealtime();
        if (finishedAt - lastInferStatusUpdateMs >= 1000L) {
          lastInferStatusUpdateMs = finishedAt;
          runOnUiThread(() -> renderLocalInferStatus(fatigue, riskCandidate));
        }
      } catch (Exception error) {
        Log.e(TAG, "Local ONNX infer failed", error);
        runOnUiThread(() -> fatigueStatusView.setText(getString(R.string.status_local_error)));
      } finally {
        if (analyzerBitmap != modelBitmap) {
          analyzerBitmap.recycle();
        }
        modelBitmap.recycle();
        inferTaskRunning.set(false);
      }
    });
  }

  @NonNull
  private Bitmap cropBestFaceBitmap(
    @NonNull Bitmap source,
    @NonNull LocalOnnxDetector.Result result
  ) {
    LocalOnnxDetector.Box best = null;
    for (LocalOnnxDetector.Box box : result.boxes) {
      if (best == null || box.score > best.score) {
        best = box;
      }
    }
    if (best == null) {
      return source;
    }

    float width = best.right - best.left;
    float height = best.bottom - best.top;
    if (width <= 1f || height <= 1f) {
      return source;
    }
    float expandX = width * FACE_CROP_MARGIN_RATIO;
    float expandY = height * FACE_CROP_MARGIN_RATIO;
    int left = Math.max(0, Math.round(best.left - expandX));
    int top = Math.max(0, Math.round(best.top - expandY));
    int right = Math.min(source.getWidth(), Math.round(best.right + expandX));
    int bottom = Math.min(source.getHeight(), Math.round(best.bottom + expandY));
    if (right <= left || bottom <= top) {
      return source;
    }
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
  }

  private void renderLocalInferStatus(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    boolean quickFatigueActive = isQuickFatigueUiActive(fatigue);
    String fatigueLine;
    if (quickFatigueActive) {
      fatigueLine = getString(R.string.status_fatigue_warning);
    } else if (riskCandidate != null && riskCandidate.getFatigueTriggered()) {
      fatigueLine = getString(R.string.status_fatigue_warning);
    } else {
      fatigueLine = getString(R.string.status_fatigue_normal);
    }
    String distractionLine = riskCandidate != null && riskCandidate.getDistractionTriggered()
      ? getString(R.string.status_distraction_warning)
      : getString(R.string.status_distraction_normal);
    fatigueStatusView.setText(
      getString(R.string.status_local_running)
        + "\n" + fatigueLine
        + "\n" + distractionLine
    );
    maybeShowFatigueWarningToast(fatigue, riskCandidate);
    maybeShowDistractionWarningToast(riskCandidate);
  }

  @Nullable
  private RiskEventCandidate updateRealtimeRisk(
    @NonNull LocalFaceSignalAnalyzer.Result faceSignals,
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    long frameTimestampMs
  ) {
    TemporalEngine currentTemporalEngine = temporalEngine;
    RiskEngine currentRiskEngine = riskEngine;
    if (faceSignals.faces <= 0) {
      realtimeRiskMissingFaceFrames++;
      if (realtimeRiskMissingFaceFrames > RISK_MISSING_FACE_TOLERANCE_FRAMES) {
        currentTemporalEngine.reset();
        currentRiskEngine.reset();
        latestRiskCandidate = null;
        return null;
      }
      return latestRiskCandidate;
    }
    realtimeRiskMissingFaceFrames = 0;

    List<DetectionResult> detections = new ArrayList<>(8);
    detections.add(new DetectionResult(
      0,
      fatigue.eyesClosed ? "eye_closed" : "eye_open",
      fatigue.eyesClosed ? fatigue.eyeClosedScore : Math.max(0.5f, 1f - fatigue.eyeClosedScore),
      new com.driveedge.infer.yolo.BoundingBox(0f, 0f, 1f, 1f),
      frameTimestampMs
    ));
    if (fatigue.mouthOpenScore >= 0.30f) {
      detections.add(new DetectionResult(
        0,
        "open_mouth",
        fatigue.mouthOpenScore,
        new com.driveedge.infer.yolo.BoundingBox(0f, 0f, 1f, 1f),
        frameTimestampMs
      ));
    }
    detections.addAll(localDistractionAnalyzer.toTemporalDetections(faceSignals, frameTimestampMs));
    FeatureWindow featureWindow = currentTemporalEngine.update(detections);
    if (featureWindow == null) {
      latestRiskCandidate = null;
      return null;
    }

    RiskEventCandidate candidate = currentRiskEngine.evaluate(featureWindow);
    latestRiskCandidate = candidate;
    return candidate;
  }

  private void maybeReportEdgeEvent(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    EdgeEventReporter reporter = edgeEventReporter;
    if (reporter == null || !captureStarted) {
      return;
    }
    if (handlePendingEdgeReport(uploadFrame, reporter)) {
      return;
    }

    EdgeRuntimeConfig config = edgeRuntimeConfig;
    if (riskCandidate != null && riskCandidate.getShouldTrigger()) {
      if (shouldDelayEvidenceReport(config) && shouldScheduleEdgeReport(config)) {
        schedulePendingRiskReport(riskCandidate, uploadFrame.capturedAtMs, config);
        lastQuickFatigueEventReportedElapsedMs = SystemClock.elapsedRealtime();
      } else if (!shouldDelayEvidenceReport(config)) {
        reporter.reportRiskCandidate(riskCandidate, buildEvidence(uploadFrame));
        lastEdgeEventScheduledElapsedMs = SystemClock.elapsedRealtime();
        lastQuickFatigueEventReportedElapsedMs = SystemClock.elapsedRealtime();
      }
      return;
    }
    if (shouldReportQuickFatigueFallback(fatigue)) {
      if (shouldDelayEvidenceReport(config) && shouldScheduleEdgeReport(config)) {
        schedulePendingFatigueReport(fatigue, uploadFrame.capturedAtMs, config);
      } else if (!shouldDelayEvidenceReport(config)) {
        reporter.reportFatigueResult(fatigue, uploadFrame.capturedAtMs, buildEvidence(uploadFrame));
        lastEdgeEventScheduledElapsedMs = SystemClock.elapsedRealtime();
      }
      lastQuickFatigueEventReportedElapsedMs = SystemClock.elapsedRealtime();
    }
  }

  private boolean handlePendingEdgeReport(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull EdgeEventReporter reporter
  ) {
    PendingEdgeReport pending = pendingEdgeReport;
    if (pending == null) {
      return false;
    }
    if (uploadFrame.capturedAtMs < pending.readyAtMs) {
      return true;
    }

    pendingEdgeReport = null;
    lastEdgeEventScheduledElapsedMs = SystemClock.elapsedRealtime();
    EdgeEventReporter.EventEvidence evidence = buildEvidence(
      uploadFrame,
      pending.triggerCapturedAtMs,
      pending.triggerCapturedAtMs - edgeRuntimeConfig.evidenceSequenceWindowMs(),
      pending.readyAtMs
    );
    if (pending.riskCandidate != null) {
      reporter.reportRiskCandidate(pending.riskCandidate, evidence);
    } else if (pending.fatigueResult != null) {
      reporter.reportFatigueResult(pending.fatigueResult, pending.triggerCapturedAtMs, evidence);
    }
    return true;
  }

  private boolean shouldDelayEvidenceReport(@NonNull EdgeRuntimeConfig config) {
    return config.evidenceEnabled() && isBufferedEvidence(config) && config.evidencePostWindowMs() > 0L;
  }

  private boolean shouldScheduleEdgeReport(@NonNull EdgeRuntimeConfig config) {
    if (pendingEdgeReport != null) {
      return false;
    }
    long lastScheduledAt = lastEdgeEventScheduledElapsedMs;
    if (lastScheduledAt <= 0L) {
      return true;
    }
    return SystemClock.elapsedRealtime() - lastScheduledAt >= Math.max(1_000L, config.debounceWindowMs());
  }

  private void schedulePendingRiskReport(
    @NonNull RiskEventCandidate riskCandidate,
    long triggerCapturedAtMs,
    @NonNull EdgeRuntimeConfig config
  ) {
    pendingEdgeReport = PendingEdgeReport.forRisk(
      riskCandidate,
      triggerCapturedAtMs,
      triggerCapturedAtMs + config.evidencePostWindowMs()
    );
    lastEdgeEventScheduledElapsedMs = SystemClock.elapsedRealtime();
    edgeEventStatusLine = "事件上报：等待后置证据";
  }

  private void schedulePendingFatigueReport(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    long triggerCapturedAtMs,
    @NonNull EdgeRuntimeConfig config
  ) {
    pendingEdgeReport = PendingEdgeReport.forFatigue(
      fatigue,
      triggerCapturedAtMs,
      triggerCapturedAtMs + config.evidencePostWindowMs()
    );
    lastEdgeEventScheduledElapsedMs = SystemClock.elapsedRealtime();
    edgeEventStatusLine = "事件上报：等待后置证据";
  }

  private void safeReportEdgeEvent(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    try {
      maybeReportEdgeEvent(uploadFrame, fatigue, riskCandidate);
    } catch (Exception error) {
      Log.e(TAG, "Edge event reporting failed", error);
      edgeEventStatusLine = "事件上报：异常";
    }
  }

  @Nullable
  private EdgeEventReporter.EventEvidence buildEvidence(@NonNull LocalUploadFrame uploadFrame) {
    long eventCapturedAtMs = uploadFrame.capturedAtMs;
    return buildEvidence(
      uploadFrame,
      eventCapturedAtMs,
      eventCapturedAtMs - edgeRuntimeConfig.evidenceSequenceWindowMs(),
      eventCapturedAtMs
    );
  }

  @Nullable
  private EdgeEventReporter.EventEvidence buildEvidence(
    @NonNull LocalUploadFrame uploadFrame,
    long eventCapturedAtMs,
    long windowStartMs,
    long windowEndMs
  ) {
    EdgeRuntimeConfig config = edgeRuntimeConfig;
    if (!config.evidenceEnabled()) {
      return null;
    }
    if (isVideoClipEvidence(config)) {
      EdgeEventReporter.EventEvidence videoEvidence = buildVideoClipEvidence(uploadFrame, config, eventCapturedAtMs, windowStartMs, windowEndMs);
      if (videoEvidence != null) {
        return videoEvidence;
      }
    }
    if (isBufferedEvidence(config)) {
      EdgeEventReporter.EventEvidence sequenceEvidence = buildFrameSequenceEvidence(uploadFrame, config, eventCapturedAtMs, windowStartMs, windowEndMs);
      if (sequenceEvidence != null) {
        return sequenceEvidence;
      }
    }
    return buildKeyFrameEvidence(uploadFrame, config, eventCapturedAtMs);
  }

  private void rememberEvidenceFrame(@NonNull LocalUploadFrame uploadFrame) {
    EdgeRuntimeConfig config = edgeRuntimeConfig;
    if (!config.evidenceEnabled() || !isBufferedEvidence(config)) {
      return;
    }
    try {
      int perFrameMaxBytes = resolveSequenceFrameMaxBytes(config);
      EncodedJpeg jpeg = encodeEvidenceBitmapFrame(uploadFrame.modelBitmap, config.evidenceJpegQuality(), perFrameMaxBytes);
      if (jpeg.jpeg.length == 0 || jpeg.jpeg.length > perFrameMaxBytes) {
        return;
      }
      evidenceFrameBuffer.offer(
        uploadFrame.capturedAtMs,
        jpeg.jpeg,
        jpeg.width,
        jpeg.height,
        evidenceBufferRetentionMs(config),
        config.evidenceSequenceSampleIntervalMs(),
        config.evidenceSequenceMaxFrames()
      );
    } catch (Exception error) {
      Log.w(TAG, "Failed to remember evidence frame", error);
    }
  }

  @Nullable
  private EdgeEventReporter.EventEvidence buildVideoClipEvidence(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull EdgeRuntimeConfig config,
    long eventCapturedAtMs,
    long windowStartMs,
    long windowEndMs
  ) {
    try {
      cleanupOldEvidenceArchivesIfNeeded();
      List<EvidenceFrameBuffer.EvidenceFrame> frames =
        evidenceFrameBuffer.snapshotRange(windowStartMs, windowEndMs, evidenceBufferRetentionMs(config), config.evidenceSequenceMaxFrames());
      if (frames.isEmpty()) {
        return null;
      }
      File clip = evidenceMp4Writer.writeClip(
        evidenceArchiveDir(),
        "alert_video",
        frames,
        eventCapturedAtMs,
        config.evidenceMaxBytes(),
        resolveVideoFrameRate(config)
      );
      if (clip == null || !clip.isFile() || clip.length() <= 0L || clip.length() > config.evidenceMaxBytes()) {
        return null;
      }
      return new EdgeEventReporter.EventEvidence(
        EVIDENCE_TYPE_VIDEO_CLIP,
        clip.toURI().toString(),
        EVIDENCE_MIME_VIDEO_MP4,
        eventCapturedAtMs
      );
    } catch (Exception error) {
      Log.w(TAG, "Failed to build evidence video clip", error);
      return null;
    }
  }

  @Nullable
  private EdgeEventReporter.EventEvidence buildFrameSequenceEvidence(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull EdgeRuntimeConfig config,
    long eventCapturedAtMs,
    long windowStartMs,
    long windowEndMs
  ) {
    try {
      cleanupOldEvidenceArchivesIfNeeded();
      List<EvidenceFrameBuffer.EvidenceFrame> frames =
        evidenceFrameBuffer.snapshotRange(windowStartMs, windowEndMs, evidenceBufferRetentionMs(config), config.evidenceSequenceMaxFrames());
      if (frames.isEmpty()) {
        return null;
      }
      File archive = evidenceFrameArchiveWriter.writeArchive(
        evidenceArchiveDir(),
        "alert_sequence",
        frames,
        eventCapturedAtMs,
        config.evidenceMaxBytes()
      );
      if (archive == null || !archive.isFile() || archive.length() <= 0L || archive.length() > config.evidenceMaxBytes()) {
        return null;
      }
      return new EdgeEventReporter.EventEvidence(
        EVIDENCE_TYPE_FRAME_SEQUENCE,
        archive.toURI().toString(),
        EVIDENCE_MIME_FRAME_SEQUENCE,
        eventCapturedAtMs
      );
    } catch (Exception error) {
      Log.w(TAG, "Failed to build evidence sequence archive", error);
      return null;
    }
  }

  @Nullable
  private EdgeEventReporter.EventEvidence buildKeyFrameEvidence(
    @NonNull LocalUploadFrame uploadFrame,
    @NonNull EdgeRuntimeConfig config,
    long eventCapturedAtMs
  ) {
    try {
      EncodedJpeg jpeg = encodeEvidenceBitmapFrame(uploadFrame.modelBitmap, config.evidenceJpegQuality(), config.evidenceMaxBytes());
      if (jpeg.jpeg.length == 0 || jpeg.jpeg.length > config.evidenceMaxBytes()) {
        return null;
      }
      String dataUrl =
        "data:" + EVIDENCE_MIME_IMAGE_JPEG + ";base64," + Base64.encodeToString(jpeg.jpeg, Base64.NO_WRAP);
      String evidenceType = isBufferedEvidence(config) || config.evidenceType().trim().isEmpty()
        ? EVIDENCE_TYPE_KEY_FRAME
        : config.evidenceType().trim();
      return new EdgeEventReporter.EventEvidence(
        evidenceType,
        dataUrl,
        EVIDENCE_MIME_IMAGE_JPEG,
        eventCapturedAtMs
      );
    } catch (Exception error) {
      Log.w(TAG, "Failed to build evidence frame", error);
      return null;
    }
  }

  private boolean isFrameSequenceEvidence(@NonNull EdgeRuntimeConfig config) {
    return EVIDENCE_TYPE_FRAME_SEQUENCE.equalsIgnoreCase(config.evidenceType())
      || EVIDENCE_MIME_FRAME_SEQUENCE.equalsIgnoreCase(config.evidenceMimeType());
  }

  private boolean isVideoClipEvidence(@NonNull EdgeRuntimeConfig config) {
    return EVIDENCE_TYPE_VIDEO_CLIP.equalsIgnoreCase(config.evidenceType())
      || EVIDENCE_MIME_VIDEO_MP4.equalsIgnoreCase(config.evidenceMimeType());
  }

  private boolean isBufferedEvidence(@NonNull EdgeRuntimeConfig config) {
    return isVideoClipEvidence(config) || isFrameSequenceEvidence(config);
  }

  private long evidenceBufferRetentionMs(@NonNull EdgeRuntimeConfig config) {
    return config.evidenceSequenceWindowMs()
      + config.evidencePostWindowMs()
      + config.evidenceSequenceSampleIntervalMs();
  }

  private int resolveSequenceFrameMaxBytes(@NonNull EdgeRuntimeConfig config) {
    int maxFrames = Math.max(1, config.evidenceSequenceMaxFrames());
    int perFrameBudget = Math.max(16 * 1024, config.evidenceMaxBytes() / maxFrames);
    return Math.min(384 * 1024, perFrameBudget);
  }

  private int resolveVideoFrameRate(@NonNull EdgeRuntimeConfig config) {
    long intervalMs = Math.max(1L, config.evidenceSequenceSampleIntervalMs());
    return Math.max(1, Math.min(30, Math.round(1000f / intervalMs)));
  }

  @NonNull
  private EncodedJpeg encodeEvidenceBitmapFrame(@NonNull Bitmap source, int initialQuality, int maxBytes) {
    int targetWidth = Math.min(640, source.getWidth());
    int quality = initialQuality;
    EncodedJpeg encoded = new EncodedJpeg(EMPTY_FRAME_BYTES, 0, 0);
    while (targetWidth >= 240) {
      int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
      Bitmap evidenceBitmap = targetWidth == source.getWidth()
        ? source
        : Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
      try {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(maxBytes, targetWidth * targetHeight));
        if (!evidenceBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
          return new EncodedJpeg(EMPTY_FRAME_BYTES, 0, 0);
        }
        encoded = new EncodedJpeg(outputStream.toByteArray(), evidenceBitmap.getWidth(), evidenceBitmap.getHeight());
        if (encoded.jpeg.length <= maxBytes || quality <= 45) {
          break;
        }
      } finally {
        if (evidenceBitmap != source) {
          evidenceBitmap.recycle();
        }
      }
      targetWidth = Math.round(targetWidth * 0.75f);
      quality = Math.max(45, quality - 8);
    }
    return encoded;
  }

  private void maybeShowFatigueWarningToast(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    if (isQuickFatigueUiActive(fatigue)) {
      // continue below
    } else if (riskCandidate != null && riskCandidate.getFatigueTriggered()) {
      // continue below
    } else {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastFatigueWarningToastMs < 3000L) {
      return;
    }
    lastFatigueWarningToastMs = now;
    Toast.makeText(
      this,
      getString(R.string.toast_fatigue_warning),
      Toast.LENGTH_SHORT
    ).show();
    playFatigueWarningTone();
  }

  private void maybeShowDistractionWarningToast(@Nullable RiskEventCandidate riskCandidate) {
    if (riskCandidate == null || !riskCandidate.getDistractionTriggered()) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastDistractionWarningToastMs < 3000L) {
      return;
    }
    lastDistractionWarningToastMs = now;
    Toast.makeText(
      this,
      getString(R.string.toast_distraction_warning),
      Toast.LENGTH_SHORT
    ).show();
    playFatigueWarningTone();
  }

  private void playFatigueWarningTone() {
    ToneGenerator toneGenerator = getOrCreateFatigueToneGenerator();
    if (toneGenerator == null) {
      return;
    }
    try {
      toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
    } catch (Exception error) {
      Log.w(TAG, "Failed to play fatigue warning tone", error);
    }
  }

  @Nullable
  private ToneGenerator getOrCreateFatigueToneGenerator() {
    ToneGenerator generator = fatigueToneGenerator;
    if (generator != null) {
      return generator;
    }
    try {
      generator = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
      fatigueToneGenerator = generator;
      return generator;
    } catch (Exception error) {
      Log.w(TAG, "Failed to create fatigue tone generator", error);
      return null;
    }
  }

  private void releaseFatigueToneGenerator() {
    ToneGenerator generator = fatigueToneGenerator;
    fatigueToneGenerator = null;
    if (generator == null) {
      return;
    }
    try {
      generator.release();
    } catch (Exception ignored) {
    }
  }

  private void releaseEdgeEventReporter() {
    EdgeEventReporter reporter = edgeEventReporter;
    edgeEventReporter = null;
    if (reporter == null) {
      return;
    }
    try {
      reporter.close();
    } catch (Exception ignored) {
    }
  }

  @NonNull
  private File evidenceArchiveDir() {
    File externalDir = getExternalFilesDir(null);
    File root = externalDir == null ? getFilesDir() : externalDir;
    File dir = new File(root, "alert_evidence");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  @NonNull
  private File ensureSubDir(@NonNull File root, @NonNull String name) {
    File dir = new File(root, name);
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  private void cleanupOldEvidenceArchivesIfNeeded() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastEvidenceArchiveCleanupMs < EVIDENCE_ARCHIVE_CLEANUP_INTERVAL_MS) {
      return;
    }
    lastEvidenceArchiveCleanupMs = now;
    File dir = evidenceArchiveDir();
    File[] files = dir.listFiles();
    if (files == null || files.length == 0) {
      return;
    }
    long cutoff = System.currentTimeMillis() - EVIDENCE_ARCHIVE_LOCAL_RETENTION_MS;
    for (File file : files) {
      if (file != null && file.isFile() && file.lastModified() > 0L && file.lastModified() < cutoff) {
        if (!file.delete()) {
          file.deleteOnExit();
        }
      }
    }
  }

  private void resetInferSession() {
    lastInferStatusUpdateMs = 0L;
    lastWaitingStatusMs = 0L;
    updateInferIdleStatus();
  }

  private void resetRealtimeRiskTracking() {
    temporalEngine.reset();
    riskEngine.reset();
    latestRiskCandidate = null;
    realtimeRiskMissingFaceFrames = 0;
    lastDistractionWarningToastMs = 0L;
    lastFatigueWarningToastMs = 0L;
    quickFatigueCandidateStartedElapsedMs = 0L;
    lastQuickFatigueEventReportedElapsedMs = 0L;
    lastEdgeEventScheduledElapsedMs = 0L;
    pendingEdgeReport = null;
    lastQuickFatigueDetectedElapsedMs = 0L;
  }

  private void updateQuickFatigueUiState(@NonNull LocalFatigueAnalyzer.Result fatigue) {
    if (!fatigue.drowsy) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (quickFatigueCandidateStartedElapsedMs <= 0L) {
      quickFatigueCandidateStartedElapsedMs = now;
    }
    lastQuickFatigueDetectedElapsedMs = now;
  }

  private boolean isQuickFatigueUiActive(@NonNull LocalFatigueAnalyzer.Result fatigue) {
    if (fatigue.drowsy) {
      return true;
    }
    long lastDetectedAt = lastQuickFatigueDetectedElapsedMs;
    if (lastDetectedAt <= 0L) {
      return false;
    }
    return SystemClock.elapsedRealtime() - lastDetectedAt <= QUICK_FATIGUE_UI_HOLD_MS;
  }

  private boolean shouldReportQuickFatigueFallback(@NonNull LocalFatigueAnalyzer.Result fatigue) {
    long now = SystemClock.elapsedRealtime();
    if (!isQuickFatigueUiActive(fatigue)) {
      quickFatigueCandidateStartedElapsedMs = 0L;
      return false;
    }
    if (quickFatigueCandidateStartedElapsedMs <= 0L) {
      quickFatigueCandidateStartedElapsedMs = now;
      return false;
    }
    if (now - quickFatigueCandidateStartedElapsedMs < QUICK_FATIGUE_EVENT_CONFIRM_MS) {
      return false;
    }
    return now - lastQuickFatigueEventReportedElapsedMs >= QUICK_FATIGUE_EVENT_COOLDOWN_MS;
  }

  private void updateInferIdleStatus() {
    fatigueStatusView.setText(getString(R.string.status_local_idle));
  }

  private void maybeUpdateWaitingStatus() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastWaitingStatusMs < WAITING_STATUS_INTERVAL_MS) {
      return;
    }
    lastWaitingStatusMs = now;
    fatigueStatusView.setText("检测模块：等待画面");
  }

  @NonNull
  private LocalOnnxDetector getOrCreateLocalDetector() throws Exception {
    synchronized (localOnnxDetectorLock) {
      LocalOnnxDetector detector = localOnnxDetector;
      if (detector != null) {
        return detector;
      }
      detector = new LocalOnnxDetector(
        getApplicationContext(),
        LOCAL_MODEL_ASSET_PATH,
        LOCAL_CONF_THRESHOLD,
        LOCAL_NMS_THRESHOLD
      );
      localOnnxDetector = detector;
      return detector;
    }
  }

  private void releaseLocalDetector() {
    synchronized (localOnnxDetectorLock) {
      LocalOnnxDetector detector = localOnnxDetector;
      localOnnxDetector = null;
      if (detector != null) {
        detector.close();
      }
    }
  }

  @NonNull
  private LocalFatigueAnalyzer getOrCreateLocalFatigueAnalyzer() {
    synchronized (localFatigueAnalyzerLock) {
      LocalFatigueAnalyzer analyzer = localFatigueAnalyzer;
      if (analyzer != null) {
        return analyzer;
      }
      analyzer = new LocalFatigueAnalyzer(FATIGUE_MISSING_FACE_TOLERANCE_FRAMES);
      localFatigueAnalyzer = analyzer;
      return analyzer;
    }
  }

  @NonNull
  private LocalFaceSignalAnalyzer getOrCreateLocalFaceSignalAnalyzer() {
    synchronized (localFaceSignalAnalyzerLock) {
      LocalFaceSignalAnalyzer analyzer = localFaceSignalAnalyzer;
      if (analyzer != null) {
        return analyzer;
      }
      analyzer = new LocalFaceSignalAnalyzer(getApplicationContext(), LOCAL_FATIGUE_MODEL_ASSET_PATH);
      localFaceSignalAnalyzer = analyzer;
      return analyzer;
    }
  }

  private void releaseLocalFaceSignalAnalyzer() {
    synchronized (localFaceSignalAnalyzerLock) {
      LocalFaceSignalAnalyzer analyzer = localFaceSignalAnalyzer;
      localFaceSignalAnalyzer = null;
      if (analyzer != null) {
        analyzer.close();
      }
    }
  }

  private void releaseLocalFatigueAnalyzer() {
    synchronized (localFatigueAnalyzerLock) {
      LocalFatigueAnalyzer analyzer = localFatigueAnalyzer;
      localFatigueAnalyzer = null;
      if (analyzer != null) {
        analyzer.close();
      }
    }
  }

  private void closeCameraResources() {
    previewRequestBuilder = null;
    aeAwbStableFrames = 0;
    aeAwbLocked = false;

    CameraCaptureSession session = captureSession;
    captureSession = null;
    if (session != null) {
      try {
        session.stopRepeating();
      } catch (Exception ignored) {
      }
      session.close();
    }

    CameraDevice device = cameraDevice;
    cameraDevice = null;
    if (device != null) {
      device.close();
    }

    ImageReader reader = imageReader;
    imageReader = null;
    if (reader != null) {
      reader.close();
    }
    synchronized (latestYuvFrameLock) {
      latestYuvFrame = null;
    }
    evidenceFrameBuffer.clear();
    pendingEdgeReport = null;
  }

  private static final class LocalUploadFrame {
    @NonNull
    final FrameData frame;
    @NonNull
    final Bitmap modelBitmap;
    final long capturedAtMs;

    LocalUploadFrame(
      @NonNull FrameData frame,
      @NonNull Bitmap modelBitmap,
      long capturedAtMs
    ) {
      this.frame = frame;
      this.modelBitmap = modelBitmap;
      this.capturedAtMs = capturedAtMs;
    }
  }

  private static final class PendingEdgeReport {
    @Nullable
    final RiskEventCandidate riskCandidate;
    @Nullable
    final LocalFatigueAnalyzer.Result fatigueResult;
    final long triggerCapturedAtMs;
    final long readyAtMs;

    private PendingEdgeReport(
      @Nullable RiskEventCandidate riskCandidate,
      @Nullable LocalFatigueAnalyzer.Result fatigueResult,
      long triggerCapturedAtMs,
      long readyAtMs
    ) {
      this.riskCandidate = riskCandidate;
      this.fatigueResult = fatigueResult;
      this.triggerCapturedAtMs = triggerCapturedAtMs;
      this.readyAtMs = Math.max(triggerCapturedAtMs, readyAtMs);
    }

    @NonNull
    static PendingEdgeReport forRisk(
      @NonNull RiskEventCandidate riskCandidate,
      long triggerCapturedAtMs,
      long readyAtMs
    ) {
      return new PendingEdgeReport(riskCandidate, null, triggerCapturedAtMs, readyAtMs);
    }

    @NonNull
    static PendingEdgeReport forFatigue(
      @NonNull LocalFatigueAnalyzer.Result fatigueResult,
      long triggerCapturedAtMs,
      long readyAtMs
    ) {
      return new PendingEdgeReport(null, fatigueResult, triggerCapturedAtMs, readyAtMs);
    }
  }

  private static final class YuvFrame {
    final int width;
    final int height;
    final long timestampNs;
    final long capturedAtMs;
    @NonNull
    final byte[] nv21;

    YuvFrame(int width, int height, long timestampNs, long capturedAtMs, @NonNull byte[] nv21) {
      this.width = width;
      this.height = height;
      this.timestampNs = timestampNs;
      this.capturedAtMs = capturedAtMs;
      this.nv21 = nv21;
    }
  }

  private static final class EncodedJpeg {
    @NonNull
    final byte[] jpeg;
    final int width;
    final int height;

    EncodedJpeg(@NonNull byte[] jpeg, int width, int height) {
      this.jpeg = jpeg;
      this.width = width;
      this.height = height;
    }
  }

}
