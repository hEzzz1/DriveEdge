package com.driveedge.app.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
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
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.DocumentsContract;
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
import com.driveedge.app.event.EdgeEventReporter;
import com.driveedge.app.fatigue.LocalDistractionAnalyzer;
import com.driveedge.app.fatigue.LocalFaceSignalAnalyzer;
import com.driveedge.app.fatigue.LocalFatigueAnalyzer;
import com.driveedge.app.fatigue.LocalOnnxDetector;
import com.driveedge.risk.engine.RiskEngine;
import com.driveedge.risk.engine.RiskEngineConfig;
import com.driveedge.risk.engine.RiskEventCandidate;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.risk.engine.RiskType;
import com.driveedge.risk.engine.TriggerReason;
import com.driveedge.temporal.engine.FeatureWindow;
import com.driveedge.temporal.engine.TemporalEngine;
import com.driveedge.temporal.engine.TemporalEngineConfig;
import com.driveedge.infer.yolo.DetectionResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
  private static final long REPLAY_DUMP_INTERVAL_MS = 1000L;
  private static final long PROBE_DUMP_INTERVAL_MS = 1000L;
  private static final int REPLAY_JPEG_QUALITY = 92;
  private static final int DEFAULT_RECORD_WIDTH = 1280;
  private static final int DEFAULT_RECORD_HEIGHT = 720;
  private static final String LOCAL_MODEL_ASSET_PATH = "models/yolov8face.onnx";
  private static final String LOCAL_FATIGUE_MODEL_ASSET_PATH = "models/face_landmarker.task";
  private static final float LOCAL_CONF_THRESHOLD = 0.25f;
  private static final float LOCAL_NMS_THRESHOLD = 0.45f;
  private static final float FACE_CROP_MARGIN_RATIO = 0.18f;
  private static final int FATIGUE_MISSING_FACE_TOLERANCE_FRAMES = 2;
  private static final int RISK_MISSING_FACE_TOLERANCE_FRAMES = 2;
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
      0.35f,
      0.45,
      0.42,
      0.50,
      1_000L,
      1_000L
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
      1_500L,
      0.50,
      0.45,
      1_000L,
      0.42,
      1_000L,
      2,
      2,
      0.03,
      0.08,
      0.48,
      0.65,
      0.80
    );
  private static final byte[] EMPTY_FRAME_BYTES = new byte[0];
  private static final String[] LOCAL_CLASS_NAMES = {"face"};

  private TextureView previewView;
  private TextView statusView;
  private TextView fatigueStatusView;
  private Button startButton;
  private Button stopButton;
  private Button recordButton;
  private Button openRecordingsButton;

  @Nullable
  private CameraManager cameraManager;
  @Nullable
  private String openedCameraId;
  private int frontCameraSensorOrientation = 0;
  private int frontCameraLensFacing = CameraCharacteristics.LENS_FACING_FRONT;
  private int recordWidth = DEFAULT_RECORD_WIDTH;
  private int recordHeight = DEFAULT_RECORD_HEIGHT;
  private int previewWidth = DEFAULT_RECORD_WIDTH;
  private int previewHeight = DEFAULT_RECORD_HEIGHT;
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

  @Nullable
  private MediaRecorder mediaRecorder;
  @Nullable
  private Surface recorderSurface;
  @Nullable
  private File recordingFile;
  private boolean isRecording = false;

  private final ExecutorService inferExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean inferTaskRunning = new AtomicBoolean(false);
  private final AtomicInteger secondFrameCounter = new AtomicInteger(0);
  private final AtomicInteger secondInferenceCounter = new AtomicInteger(0);

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
  private long lastReplayDumpMs = 0L;
  private long lastProbeDumpMs = 0L;
  private long lastWaitingStatusMs = 0L;
  private long lastFatigueWarningToastMs = 0L;
  private long lastDistractionWarningToastMs = 0L;
  private long quickFatigueCandidateStartedElapsedMs = 0L;
  private long lastQuickFatigueEventReportedElapsedMs = 0L;
  private int realtimeRiskMissingFaceFrames = 0;
  private long lastQuickFatigueDetectedElapsedMs = 0L;
  @NonNull
  private String lastQuickFatigueEventSummary = "fatigue_normal";
  @Nullable
  private ToneGenerator fatigueToneGenerator;
  @Nullable
  private EdgeEventReporter edgeEventReporter;
  @NonNull
  private volatile String edgeEventStatusLine = "事件上报：待触发";
  @NonNull
  private final TemporalEngine temporalEngine = new TemporalEngine(REALTIME_TEMPORAL_CONFIG);
  @NonNull
  private final RiskEngine riskEngine = new RiskEngine(REALTIME_RISK_CONFIG);
  @Nullable
  private volatile RiskEventCandidate latestRiskCandidate;
  @NonNull
  private String inferSessionId = "session-init";
  @NonNull
  private final Object latestYuvFrameLock = new Object();
  @Nullable
  private YuvFrame latestYuvFrame;

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
    startButton = findViewById(R.id.startButton);
    stopButton = findViewById(R.id.stopButton);
    recordButton = findViewById(R.id.recordButton);
    openRecordingsButton = findViewById(R.id.openRecordingsButton);

    cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

    previewView.setSurfaceTextureListener(surfaceTextureListener);
    startButton.setOnClickListener(v -> ensurePermissionThenStart());
    stopButton.setOnClickListener(v -> stopCapture());
    recordButton.setOnClickListener(v -> toggleRecording());
    openRecordingsButton.setOnClickListener(v -> openRecordingsDirectory());

    stopButton.setEnabled(false);
    recordButton.setEnabled(false);
    updateRecordButton();
    resetInferSession();
    edgeEventReporter =
      new EdgeEventReporter(
        getApplicationContext(),
        statusLine -> runOnUiThread(() -> edgeEventStatusLine = statusLine)
      );
    statusView.setText(getString(R.string.status_idle));
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
    super.onDestroy();
  }

  private void ensurePermissionThenStart() {
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
    statusView.setText(getString(R.string.status_connected));
    startButton.setEnabled(false);
    stopButton.setEnabled(true);
    recordButton.setEnabled(true);
    updateRecordButton();
    startCameraThreadIfNeeded();
    startPreviewUploadLoop();
    if (previewView.isAvailable()) {
      openCameraIfReady();
    }
  }

  private void stopCapture() {
    if (!captureStarted) {
      return;
    }
    captureStarted = false;
    stopRecordingInternal(false);
    closeCameraResources();
    stopCameraThreadIfNeeded();
    stopPreviewUploadLoop();
    secondFrameCounter.set(0);
    secondInferenceCounter.set(0);
    resetRealtimeRiskTracking();
    statusView.setText(getString(R.string.status_stopped));
    fatigueStatusView.setText(getString(R.string.status_local_stopped));
    startButton.setEnabled(true);
    stopButton.setEnabled(false);
    recordButton.setEnabled(false);
    updateRecordButton();
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
      statusView.setText(getString(R.string.status_error, error.getClass().getSimpleName()));
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
        updateRecordingSize(characteristics);
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
    updateRecordingSize(characteristics);
    updatePreviewSize(characteristics);
    updateAnalysisConfig(characteristics);
    updateQualityModeConfig(characteristics);
    return fallback;
  }

  private void updateRecordingSize(@NonNull CameraCharacteristics characteristics) {
    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size selected = chooseRecordingSize(map == null ? null : map.getOutputSizes(MediaRecorder.class));
    recordWidth = selected.getWidth();
    recordHeight = selected.getHeight();
  }

  private void updatePreviewSize(@NonNull CameraCharacteristics characteristics) {
    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size selected = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class), recordWidth, recordHeight);
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
  private Size chooseRecordingSize(@Nullable Size[] sizes) {
    if (sizes == null || sizes.length == 0) {
      return new Size(DEFAULT_RECORD_WIDTH, DEFAULT_RECORD_HEIGHT);
    }

    for (Size size : sizes) {
      if (size.getWidth() == 1280 && size.getHeight() == 720) {
        return size;
      }
    }
    for (Size size : sizes) {
      if (size.getWidth() == 1920 && size.getHeight() == 1080) {
        return size;
      }
    }

    Size bestUnder720p = null;
    for (Size size : sizes) {
      if (size.getWidth() <= 1280 && size.getHeight() <= 720) {
        if (bestUnder720p == null || (size.getWidth() * size.getHeight()) > (bestUnder720p.getWidth() * bestUnder720p.getHeight())) {
          bestUnder720p = size;
        }
      }
    }
    if (bestUnder720p != null) {
      return bestUnder720p;
    }

    Size largest = sizes[0];
    for (Size size : sizes) {
      if ((size.getWidth() * size.getHeight()) > (largest.getWidth() * largest.getHeight())) {
        largest = size;
      }
    }
    return largest;
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
      runOnUiThread(() -> statusView.setText(getString(R.string.status_camera_failed, error)));
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
      if (withRecorder) {
        // Some devices show green recording when analysis stream is kept alive.
        if (reader != null) {
          reader.close();
          imageReader = null;
          reader = null;
        }
        synchronized (latestYuvFrameLock) {
          latestYuvFrame = null;
        }
      } else if (reader == null) {
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
      // Some devices produce green recordings when recorder + YUV analysis run together.
      // During recording, keep only preview + recorder streams.
      if (!withRecorder && yuvSurface != null) {
        surfaces.add(yuvSurface);
      }
      if (withRecorder && recorderSurface != null) {
        surfaces.add(recorderSurface);
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

      int template = withRecorder ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
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
              CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(template);
              builder.addTarget(previewSurface);
              if (!withRecorder && yuvSurface != null) {
                builder.addTarget(yuvSurface);
              }
              if (withRecorder && recorderSurface != null) {
                builder.addTarget(recorderSurface);
              }
              applyHighQualityCaptureParams(builder, withRecorder);
              if (!withRecorder) {
                previewRequestBuilder = builder;
                aeAwbStableFrames = 0;
                aeAwbLocked = !aeLockAvailable && !awbLockAvailable;
              } else {
                previewRequestBuilder = null;
              }
              session.setRepeatingRequest(
                builder.build(),
                withRecorder ? null : previewCaptureCallback,
                handler
              );

              if (withRecorder && startRecorderOnConfigured) {
                startMediaRecorderAfterSessionReady();
              }
            } catch (Exception error) {
              runOnUiThread(() -> statusView.setText(getString(R.string.status_error, error.getClass().getSimpleName())));
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
      statusView.setText(getString(R.string.status_error, error.getClass().getSimpleName()));
    }
  }

  private void applyHighQualityCaptureParams(@NonNull CaptureRequest.Builder builder, boolean withRecorder) {
    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    if (!withRecorder) {
      builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
    }

    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
    builder.set(CaptureRequest.EDGE_MODE, edgeMode);
    builder.set(CaptureRequest.TONEMAP_MODE, toneMapMode);

    if (!withRecorder) {
      if (aeLockAvailable) {
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
      }
      if (awbLockAvailable) {
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
      }
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
      if (captureStarted && !isRecording) {
        maybeDumpProbeFrame();
        if (tryReserveInferSlot()) {
          LocalUploadFrame localFrame = convertYuvToLocalUploadFrame();
          if (localFrame != null) {
            dispatchLocalInferFrame(localFrame);
            onFrameStatusTick(localFrame.frame);
          } else {
            maybeUpdateWaitingStatus();
            inferTaskRunning.set(false);
          }
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
      EncodedJpeg localFrame = encodeNormalizedNv21ToJpeg(yuvFrame, REPLAY_JPEG_QUALITY);
      Bitmap modelBitmap = createModelBitmapFromYuvFrame(yuvFrame);
      FrameData frame = new FrameData(
        localFrame.width,
        localFrame.height,
        0,
        yuvFrame.timestampNs,
        EMPTY_FRAME_BYTES
      );
      return new LocalUploadFrame(
        frame,
        localFrame.jpeg,
        localFrame.jpeg,
        modelBitmap,
        "raw"
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
    return new YuvFrame(width, height, timestampNs, nv21);
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

  private int computeVideoOrientationHint() {
    int displayDegrees = getDisplayRotationDegrees();
    if (frontCameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
      int result = (frontCameraSensorOrientation + displayDegrees) % 360;
      return (360 - result) % 360;
    }
    return (frontCameraSensorOrientation - displayDegrees + 360) % 360;
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
    runOnUiThread(() -> statusView.setText(getString(
      R.string.status_running,
      frame.width,
      frame.height,
      frame.rotationDegrees,
      fps,
      frame.timestampNs
    )));
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
        safeReportEdgeEvent(fatigue, riskCandidate);
        secondInferenceCounter.incrementAndGet();
        maybeDumpQualityReplay(uploadFrame, result);
        maybeSaveLocalRecognizedPhoto(uploadFrame, result);

        long finishedAt = SystemClock.elapsedRealtime();
        if (finishedAt - lastInferStatusUpdateMs >= 1000L) {
          int inferFps = secondInferenceCounter.getAndSet(0);
          lastInferStatusUpdateMs = finishedAt;
          runOnUiThread(() -> renderLocalInferStatus(result, fatigue, riskCandidate, inferFps));
        }
      } catch (Exception error) {
        Log.e(TAG, "Local ONNX infer failed", error);
        runOnUiThread(() -> fatigueStatusView.setText(
          getString(R.string.status_local_error, formatError(error))
        ));
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
    @NonNull LocalOnnxDetector.Result result,
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate,
    int inferFps
  ) {
    String base = getString(
      R.string.status_local_running,
      inferFps,
      result.totalLatencyMs,
      result.inferenceLatencyMs,
      result.detections,
      result.maxScore,
      result.outputShape
    );
    boolean quickFatigueActive = isQuickFatigueUiActive(fatigue);
    String fatigueLine;
    if (quickFatigueActive) {
      fatigueLine = getString(R.string.status_fatigue_warning, resolveQuickFatigueSummary(fatigue));
    } else if (riskCandidate != null && riskCandidate.getFatigueTriggered()) {
      fatigueLine = getString(R.string.status_fatigue_warning, formatRiskCandidate(riskCandidate));
    } else {
      fatigueLine = getString(R.string.status_fatigue_normal);
    }
    String distractionLine = riskCandidate != null && riskCandidate.getDistractionTriggered()
      ? getString(R.string.status_distraction_warning, formatRiskCandidate(riskCandidate))
      : getString(R.string.status_distraction_normal);
    fatigueStatusView.setText(
      base
        + "\n" + fatigueLine
        + "\n" + distractionLine
        + "\n" + fatigue.summaryText()
        + "\n" + formatRiskScores(riskCandidate)
        + "\n" + edgeEventStatusLine
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
    if (faceSignals.faces <= 0) {
      realtimeRiskMissingFaceFrames++;
      if (realtimeRiskMissingFaceFrames > RISK_MISSING_FACE_TOLERANCE_FRAMES) {
        temporalEngine.reset();
        riskEngine.reset();
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
    FeatureWindow featureWindow = temporalEngine.update(detections);
    if (featureWindow == null) {
      latestRiskCandidate = null;
      return null;
    }

    RiskEventCandidate candidate = riskEngine.evaluate(featureWindow);
    latestRiskCandidate = candidate;
    return candidate;
  }

  private void maybeReportEdgeEvent(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    EdgeEventReporter reporter = edgeEventReporter;
    if (reporter == null) {
      return;
    }
    if (riskCandidate != null && riskCandidate.getShouldTrigger()) {
      reporter.reportRiskCandidate(riskCandidate);
      lastQuickFatigueEventReportedElapsedMs = SystemClock.elapsedRealtime();
      return;
    }
    if (shouldReportQuickFatigueFallback(fatigue)) {
      reporter.reportFatigueResult(fatigue, System.currentTimeMillis());
      lastQuickFatigueEventReportedElapsedMs = SystemClock.elapsedRealtime();
    }
  }

  private void safeReportEdgeEvent(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    try {
      maybeReportEdgeEvent(fatigue, riskCandidate);
    } catch (Exception error) {
      Log.e(TAG, "Edge event reporting failed", error);
      edgeEventStatusLine = "事件上报：异常 " + error.getClass().getSimpleName();
    }
  }

  private void maybeShowFatigueWarningToast(
    @NonNull LocalFatigueAnalyzer.Result fatigue,
    @Nullable RiskEventCandidate riskCandidate
  ) {
    String warningText;
    if (isQuickFatigueUiActive(fatigue)) {
      warningText = resolveQuickFatigueSummary(fatigue);
    } else if (riskCandidate != null && riskCandidate.getFatigueTriggered()) {
      warningText = formatRiskCandidate(riskCandidate);
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
      getString(R.string.toast_fatigue_warning, warningText),
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
      getString(R.string.toast_distraction_warning, formatRiskCandidate(riskCandidate)),
      Toast.LENGTH_SHORT
    ).show();
    playFatigueWarningTone();
  }

  @NonNull
  private String formatRiskCandidate(@NonNull RiskEventCandidate candidate) {
    String type =
      candidate.getDominantRiskType() == RiskType.DISTRACTION ? "DISTRACTION" :
        candidate.getDominantRiskType() == RiskType.FATIGUE ? "FATIGUE" : "NONE";
    return candidate.getRiskLevel().name() + " " + type + " " + formatTriggerReasons(candidate);
  }

  @NonNull
  private String formatRiskScores(@Nullable RiskEventCandidate candidate) {
    if (candidate == null) {
      return "risk=fatigue 0.00 distraction 0.00 level=NONE";
    }
    return String.format(
      Locale.US,
      "risk=fatigue %.2f distraction %.2f level=%s",
      candidate.getFatigueScore(),
      candidate.getDistractionScore(),
      candidate.getRiskLevel().name()
    );
  }

  @NonNull
  private String formatTriggerReasons(@NonNull RiskEventCandidate candidate) {
    if (candidate.getTriggerReasons().isEmpty()) {
      return "stable";
    }
    List<String> labels = new ArrayList<>();
    for (TriggerReason reason : candidate.getTriggerReasons()) {
      switch (reason) {
        case DISTRACTION_HEAD_OFF_ROAD_SUSTAINED:
          labels.add("off_road");
          break;
        case DISTRACTION_HEAD_DOWN_SUSTAINED:
          labels.add("head_down");
          break;
        case DISTRACTION_GAZE_OFFSET_SUSTAINED:
          labels.add("gaze_offset");
          break;
        case FATIGUE_PERCLOS_SUSTAINED:
          labels.add("perclos");
          break;
        case FATIGUE_YAWN_FREQUENT:
          labels.add("yawn");
          break;
        default:
          labels.add(reason.name().toLowerCase(Locale.US));
          break;
      }
    }
    return labels.toString();
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

  private void maybeDumpQualityReplay(@NonNull LocalUploadFrame frame, @NonNull LocalOnnxDetector.Result result) {
    long now = SystemClock.elapsedRealtime();
    if (now - lastReplayDumpMs < REPLAY_DUMP_INTERVAL_MS) {
      return;
    }
    lastReplayDumpMs = now;

    try {
      long tsMs = frame.frame.timestampNs / 1_000_000L;
      String prefix = String.format(Locale.US, "%d_%s", tsMs, frame.qualityTag);

      File root = qualityReplayDir();
      File rawDir = ensureSubDir(root, "raw");
      File enhancedDir = ensureSubDir(root, "enhanced");
      File modelDir = ensureSubDir(root, "model");
      File overlayDir = ensureSubDir(root, "overlay");

      writeBytes(frame.rawJpeg, new File(rawDir, prefix + "_raw.jpg"));
      writeBytes(frame.enhancedJpeg, new File(enhancedDir, prefix + "_enhanced.jpg"));
      writeBitmapJpeg(frame.modelBitmap, new File(modelDir, prefix + "_model.jpg"), REPLAY_JPEG_QUALITY);

      Bitmap overlay = drawOverlay(frame.modelBitmap, result);
      try {
        writeBitmapJpeg(overlay, new File(overlayDir, prefix + "_overlay.jpg"), REPLAY_JPEG_QUALITY);
      } finally {
        overlay.recycle();
      }
    } catch (Exception error) {
      Log.w(TAG, "Failed to dump quality replay", error);
    }
  }

  private void maybeSaveLocalRecognizedPhoto(@NonNull LocalUploadFrame frame, @NonNull LocalOnnxDetector.Result result) {
    if (result.detections <= 0) {
      return;
    }

    try {
      long tsMs = frame.frame.timestampNs / 1_000_000L;
      String prefix = String.format(Locale.US, "%d_det%d_%.2f", tsMs, result.detections, result.maxScore);

      File root = qualityReplayDir();
      File detectedDir = ensureSubDir(root, "local_detected");

      writeBitmapJpeg(frame.modelBitmap, new File(detectedDir, prefix + "_frame.jpg"), REPLAY_JPEG_QUALITY);
      Bitmap overlay = drawOverlay(frame.modelBitmap, result);
      try {
        writeBitmapJpeg(overlay, new File(detectedDir, prefix + "_overlay.jpg"), REPLAY_JPEG_QUALITY);
      } finally {
        overlay.recycle();
      }
    } catch (Exception error) {
      Log.w(TAG, "Failed to save local recognized photo", error);
    }
  }

  private void maybeDumpProbeFrame() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastProbeDumpMs < PROBE_DUMP_INTERVAL_MS) {
      return;
    }
    lastProbeDumpMs = now;

    try {
      YuvFrame yuvFrame = getLatestYuvFrame();
      if (yuvFrame == null) {
        return;
      }
      EncodedJpeg jpeg = encodeNormalizedNv21ToJpeg(yuvFrame, REPLAY_JPEG_QUALITY);
      long tsMs = yuvFrame.timestampNs / 1_000_000L;
      String source = "camera2_yuv";

      File root = qualityReplayDir();
      File probeDir = ensureSubDir(root, "probe");
      String filename = String.format(Locale.US, "%d_probe_%s.jpg", tsMs, source);
      writeBytes(jpeg.jpeg, new File(probeDir, filename));
    } catch (Exception error) {
      Log.w(TAG, "Failed to dump probe frame", error);
    }
  }

  @NonNull
  private Bitmap drawOverlay(@NonNull Bitmap input, @NonNull LocalOnnxDetector.Result result) {
    Bitmap overlay = input.copy(Bitmap.Config.ARGB_8888, true);
    Canvas canvas = new Canvas(overlay);

    Paint boxPaint = new Paint();
    boxPaint.setStyle(Paint.Style.STROKE);
    boxPaint.setStrokeWidth(3f);
    boxPaint.setColor(Color.GREEN);

    Paint textPaint = new Paint();
    textPaint.setColor(Color.YELLOW);
    textPaint.setTextSize(20f);
    textPaint.setStyle(Paint.Style.FILL);

    for (LocalOnnxDetector.Box box : result.boxes) {
      boxPaint.setColor(colorForClass(box.classId));
      canvas.drawRect(box.left, box.top, box.right, box.bottom, boxPaint);
      String label = localClassName(box.classId) + String.format(Locale.US, " %.2f", box.score);
      float textY = Math.max(18f, box.top - 6f);
      canvas.drawText(label, box.left + 2f, textY, textPaint);
    }
    return overlay;
  }

  @NonNull
  private String truncate(@NonNull String value, int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    return value.substring(0, Math.max(0, maxChars - 1)) + "...";
  }

  private int colorForClass(int classId) {
    switch (classId) {
      case 0:
        return Color.RED;
      case 1:
        return Color.CYAN;
      case 2:
        return Color.GREEN;
      case 3:
        return Color.YELLOW;
      default:
        return Color.WHITE;
    }
  }

  @NonNull
  private String localClassName(int classId) {
    if (classId >= 0 && classId < LOCAL_CLASS_NAMES.length) {
      return LOCAL_CLASS_NAMES[classId];
    }
    return "cls" + classId;
  }

  private void writeBitmapJpeg(@NonNull Bitmap bitmap, @NonNull File output, int quality) throws Exception {
    File parent = output.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Failed to create replay directory");
    }
    try (FileOutputStream stream = new FileOutputStream(output, false)) {
      boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
      if (!ok) {
        throw new IOException("Failed to compress bitmap");
      }
      stream.flush();
    }
  }

  private void writeBytes(@NonNull byte[] data, @NonNull File output) throws Exception {
    File parent = output.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Failed to create replay directory");
    }
    try (FileOutputStream stream = new FileOutputStream(output, false)) {
      stream.write(data);
      stream.flush();
    }
  }

  @NonNull
  private File qualityReplayDir() {
    File externalDir = getExternalFilesDir(null);
    File root = externalDir == null ? getFilesDir() : externalDir;
    File dir = new File(root, "quality_replay");
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

  private void resetInferSession() {
    inferSessionId = "sess-" + System.currentTimeMillis();
    secondInferenceCounter.set(0);
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
    lastQuickFatigueDetectedElapsedMs = 0L;
    lastQuickFatigueEventSummary = "fatigue_normal";
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
    lastQuickFatigueEventSummary = fatigue.eventSummary;
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

  @NonNull
  private String resolveQuickFatigueSummary(@NonNull LocalFatigueAnalyzer.Result fatigue) {
    if (fatigue.drowsy) {
      return fatigue.eventSummary;
    }
    return lastQuickFatigueEventSummary;
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
    fatigueStatusView.setText(getString(
      R.string.status_local_idle,
      LOCAL_MODEL_ASSET_PATH,
      inferSessionId
    ));
  }

  private void maybeUpdateWaitingStatus() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastWaitingStatusMs < WAITING_STATUS_INTERVAL_MS) {
      return;
    }
    lastWaitingStatusMs = now;
    fatigueStatusView.setText("本地疲劳快检：等待相机帧");
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

  @NonNull
  private String formatError(@NonNull Throwable error) {
    StringBuilder builder = new StringBuilder(error.getClass().getSimpleName());
    Throwable cursor = error;
    int depth = 0;
    while (cursor != null && depth < 4) {
      String message = cursor.getMessage();
      if (message != null && !message.trim().isEmpty()) {
        if (depth == 0) {
          builder.append(": ").append(message);
        } else {
          builder.append(" | cause").append(depth).append(": ").append(message);
        }
      }
      cursor = cursor.getCause();
      depth++;
    }
    return builder.toString();
  }

  private void toggleRecording() {
    if (!captureStarted) {
      return;
    }
    if (isRecording) {
      stopRecordingInternal(true);
      return;
    }
    startRecordingInternal();
  }

  private void startRecordingInternal() {
    Handler handler = cameraHandler;
    if (handler == null || cameraDevice == null) {
      Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_SHORT).show();
      return;
    }
    handler.post(() -> {
      try {
        prepareMediaRecorder();
        createCaptureSession(true, true);
      } catch (Exception error) {
        releaseMediaRecorder();
        runOnUiThread(() -> Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_SHORT).show());
      }
    });
  }

  private void startMediaRecorderAfterSessionReady() {
    MediaRecorder recorder = mediaRecorder;
    if (recorder == null) {
      return;
    }
    try {
      recorder.start();
      isRecording = true;
      stopPreviewUploadLoop();
      runOnUiThread(() -> {
        updateRecordButton();
        String path = recordingFile == null ? "-" : recordingFile.getAbsolutePath();
        fatigueStatusView.setText("录制中：已暂停分析流（防止绿屏）");
        Toast.makeText(this, getString(R.string.recording_started, path), Toast.LENGTH_LONG).show();
      });
    } catch (Exception error) {
      isRecording = false;
      runOnUiThread(() -> Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_SHORT).show());
    }
  }

  private void stopRecordingInternal(boolean recreatePreviewSession) {
    Handler handler = cameraHandler;
    if (handler == null) {
      return;
    }
    handler.post(() -> {
      if (!isRecording && mediaRecorder == null) {
        return;
      }

      File output = recordingFile;
      try {
        MediaRecorder recorder = mediaRecorder;
        if (recorder != null && isRecording) {
          recorder.stop();
        }
      } catch (Exception stopError) {
        if (output != null && output.exists()) {
          output.delete();
        }
      } finally {
        isRecording = false;
        releaseMediaRecorder();
        runOnUiThread(() -> {
          updateRecordButton();
          updateInferIdleStatus();
          if (output != null && output.exists()) {
            Toast.makeText(this, getString(R.string.recording_stopped, output.getAbsolutePath()), Toast.LENGTH_LONG).show();
          }
        });
        if (recreatePreviewSession && captureStarted) {
          createCaptureSession(false, false);
          startPreviewUploadLoop();
        }
      }
    });
  }

  private void prepareMediaRecorder() throws IOException {
    File output = newRecordingFile();
    MediaRecorder recorder = mediaRecorder;
    if (recorder == null) {
      recorder = new MediaRecorder();
      mediaRecorder = recorder;
    } else {
      recorder.reset();
    }

    recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    recorder.setOutputFile(output.getAbsolutePath());
    recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
    recorder.setVideoFrameRate(24);
    int targetBitrate = Math.max(2_000_000, Math.min(12_000_000, recordWidth * recordHeight * 5));
    recorder.setVideoEncodingBitRate(targetBitrate);
    recorder.setVideoSize(recordWidth, recordHeight);
    recorder.setOrientationHint(computeVideoOrientationHint());
    recorder.prepare();
    recorderSurface = recorder.getSurface();
    recordingFile = output;
  }

  private void releaseMediaRecorder() {
    MediaRecorder recorder = mediaRecorder;
    mediaRecorder = null;
    recorderSurface = null;
    if (recorder == null) {
      return;
    }
    try {
      recorder.reset();
    } catch (Exception ignored) {
    }
    try {
      recorder.release();
    } catch (Exception ignored) {
    }
  }

  @NonNull
  private File recordingsDir() {
    File externalDir = getExternalFilesDir(null);
    File root = externalDir == null ? getFilesDir() : externalDir;
    File dir = new File(root, "recordings");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  @NonNull
  private File newRecordingFile() throws IOException {
    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    File output = new File(recordingsDir(), "capture_" + ts + ".mp4");
    if (output.exists() && !output.delete()) {
      throw new IOException("Failed to replace existing output file");
    }
    return output;
  }

  private void openRecordingsDirectory() {
    openExternalFilesSubDirectory("recordings", recordingsDir());
  }

  private void openExternalFilesSubDirectory(@NonNull String subdir, @NonNull File fallbackDir) {
    String docId = "primary:Android/data/" + getPackageName() + "/files/" + subdir;
    Uri initialUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);

    Intent directOpenIntent = new Intent(Intent.ACTION_VIEW);
    directOpenIntent.setDataAndType(initialUri, DocumentsContract.Document.MIME_TYPE_DIR);
    directOpenIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    directOpenIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

    if (canHandleIntent(directOpenIntent)) {
      try {
        startActivity(directOpenIntent);
        return;
      } catch (ActivityNotFoundException ignored) {
      } catch (SecurityException ignored) {
      }
    }

    Intent treeIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    treeIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    treeIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    treeIntent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      treeIntent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
    }

    if (canHandleIntent(treeIntent)) {
      try {
        startActivity(treeIntent);
        return;
      } catch (ActivityNotFoundException ignored) {
      } catch (SecurityException ignored) {
      }
    }

    Toast.makeText(
      this,
      getString(R.string.open_recordings_fallback, fallbackDir.getAbsolutePath()),
      Toast.LENGTH_LONG
    ).show();
  }

  private void updateRecordButton() {
    recordButton.setText(isRecording ? R.string.record_stop : R.string.record_start);
  }

  private boolean canHandleIntent(@NonNull Intent intent) {
    return intent.resolveActivity(getPackageManager()) != null;
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

    releaseMediaRecorder();
    isRecording = false;
  }

  private static final class LocalUploadFrame {
    @NonNull
    final FrameData frame;
    @NonNull
    final byte[] rawJpeg;
    @NonNull
    final byte[] enhancedJpeg;
    @NonNull
    final Bitmap modelBitmap;
    @NonNull
    final String qualityTag;

    LocalUploadFrame(
      @NonNull FrameData frame,
      @NonNull byte[] rawJpeg,
      @NonNull byte[] enhancedJpeg,
      @NonNull Bitmap modelBitmap,
      @NonNull String qualityTag
    ) {
      this.frame = frame;
      this.rawJpeg = rawJpeg;
      this.enhancedJpeg = enhancedJpeg;
      this.modelBitmap = modelBitmap;
      this.qualityTag = qualityTag;
    }
  }

  private static final class YuvFrame {
    final int width;
    final int height;
    final long timestampNs;
    @NonNull
    final byte[] nv21;

    YuvFrame(int width, int height, long timestampNs, @NonNull byte[] nv21) {
      this.width = width;
      this.height = height;
      this.timestampNs = timestampNs;
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
