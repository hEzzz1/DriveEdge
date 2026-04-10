package com.driveedge.app.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.driveedge.app.BuildConfig;
import com.driveedge.app.R;
import com.driveedge.app.camera.CameraForegroundService;
import com.driveedge.app.camera.FrameData;
import com.driveedge.event.center.EdgeEvent;
import com.driveedge.event.center.UploadStatus;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.uploader.EventUploader;
import com.driveedge.uploader.HttpEventsApiTransport;
import com.driveedge.uploader.UploadReceipt;
import com.driveedge.uploader.UploaderConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
  private static final Duration NETWORK_CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration NETWORK_REQUEST_TIMEOUT = Duration.ofSeconds(4);

  private PreviewView previewView;
  private TextView statusView;
  private TextView networkStatusView;
  private Button startButton;
  private Button stopButton;
  private Button checkNetworkButton;
  private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

  @Nullable
  private CameraForegroundService captureService;

  private boolean isBound;
  private volatile boolean networkCheckRunning = false;
  private final AtomicInteger secondFrameCounter = new AtomicInteger(0);
  private long lastStatusUpdateMs = 0L;

  private final CameraForegroundService.FrameListener frameListener = this::onFrame;

  private final ServiceConnection serviceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      if (!(service instanceof CameraForegroundService.LocalBinder)) {
        return;
      }

      CameraForegroundService.LocalBinder binder = (CameraForegroundService.LocalBinder) service;
      captureService = binder.getService();
      isBound = true;

      captureService.attachPreview(previewView.getSurfaceProvider());
      captureService.setFrameListener(frameListener);

      statusView.setText(getString(R.string.status_connected));
      startButton.setEnabled(false);
      stopButton.setEnabled(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      captureService = null;
      isBound = false;
      startButton.setEnabled(true);
      stopButton.setEnabled(false);
      statusView.setText(getString(R.string.status_disconnected));
    }
  };

  private final ActivityResultLauncher<String[]> permissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
      boolean allGranted = areAllPermissionsGranted(result);
      if (!allGranted) {
        Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_SHORT).show();
        return;
      }
      startCaptureAndBindService();
    });

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    previewView = findViewById(R.id.previewView);
    statusView = findViewById(R.id.statusView);
    networkStatusView = findViewById(R.id.networkStatusView);
    startButton = findViewById(R.id.startButton);
    stopButton = findViewById(R.id.stopButton);
    checkNetworkButton = findViewById(R.id.checkNetworkButton);

    startButton.setOnClickListener(view -> ensurePermissionsThenStart());
    stopButton.setOnClickListener(view -> stopCaptureService());
    checkNetworkButton.setOnClickListener(view -> probeNetworkStatus());

    statusView.setText(getString(R.string.status_idle_with_uploader, BuildConfig.DRIVESERVER_BASE_URL));
    networkStatusView.setText(String.format(
      Locale.getDefault(),
      "%s\n%s",
      getString(R.string.network_status_target, eventsEndpointUrl()),
      getString(R.string.network_status_idle)
    ));
    stopButton.setEnabled(false);
    probeNetworkStatus();
  }

  @Override
  protected void onDestroy() {
    if (isBound) {
      captureService.setFrameListener(null);
      captureService.detachPreview();
      unbindService(serviceConnection);
      isBound = false;
    }
    networkExecutor.shutdownNow();
    super.onDestroy();
  }

  private void onFrame(@NonNull FrameData frame) {
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

  private boolean areAllPermissionsGranted(@NonNull Map<String, Boolean> result) {
    for (Boolean granted : result.values()) {
      if (!Boolean.TRUE.equals(granted)) {
        return false;
      }
    }
    return true;
  }

  private void probeNetworkStatus() {
    if (networkCheckRunning) {
      return;
    }
    networkCheckRunning = true;
    checkNetworkButton.setEnabled(false);
    networkStatusView.setText(String.format(
      Locale.getDefault(),
      "%s\n%s",
      getString(R.string.network_status_target, eventsEndpointUrl()),
      getString(R.string.network_status_checking)
    ));

    networkExecutor.execute(() -> {
      long startMs = SystemClock.elapsedRealtime();
      NetworkProbeResult result = executeNetworkProbe();
      long costMs = SystemClock.elapsedRealtime() - startMs;
      runOnUiThread(() -> {
        networkCheckRunning = false;
        checkNetworkButton.setEnabled(true);
        renderProbeResult(result, costMs);
      });
    });
  }

  @NonNull
  private NetworkProbeResult executeNetworkProbe() {
    UploaderConfig uploaderConfig = new UploaderConfig(
      BuildConfig.DRIVESERVER_BASE_URL,
      BuildConfig.DRIVESERVER_DEVICE_TOKEN,
      "/api/v1/events",
      NETWORK_CONNECT_TIMEOUT,
      NETWORK_REQUEST_TIMEOUT
    );
    String endpoint = uploaderConfig.endpointUrl();
    try {
      EventUploader uploader = new EventUploader(
        uploaderConfig,
        new HttpEventsApiTransport(uploaderConfig.getConnectTimeout())
      );
      UploadReceipt receipt = uploader.upload(buildProbeEvent());
      if (receipt.getTransportError() != null) {
        return NetworkProbeResult.failure(endpoint, receipt.getTransportError());
      }

      Integer httpStatus = receipt.getHttpStatus();
      return NetworkProbeResult.response(
        endpoint,
        httpStatus == null ? 0 : httpStatus,
        receipt.getCode(),
        receipt.getTraceId()
      );
    } catch (Exception error) {
      String message = error.getClass().getSimpleName();
      if (error.getMessage() != null && !error.getMessage().isEmpty()) {
        message += ": " + error.getMessage();
      }
      return NetworkProbeResult.failure(endpoint, message);
    }
  }

  private void renderProbeResult(@NonNull NetworkProbeResult result, long costMs) {
    String endpointLine = getString(R.string.network_status_target, result.endpoint);
    if (result.errorMessage != null) {
      networkStatusView.setText(String.format(
        Locale.getDefault(),
        "%s\n%s",
        endpointLine,
        getString(R.string.network_status_failed, result.errorMessage)
      ));
      return;
    }

    String codeText = result.businessCode == null ? "-" : String.valueOf(result.businessCode);
    String traceIdText = (result.traceId == null || result.traceId.isEmpty()) ? "-" : result.traceId;
    String resultLine = getString(
      R.string.network_status_result,
      result.httpStatus,
      codeText,
      traceIdText,
      costMs
    );

    String diagnosis;
    if (result.httpStatus == 401 || (result.businessCode != null && result.businessCode == 40101)) {
      diagnosis = "链路已通，服务端已返回鉴权失败（token 无效或过期）";
    } else if (result.httpStatus >= 200 && result.httpStatus < 300) {
      diagnosis = "链路已通，客户端与服务端通信正常";
    } else {
      diagnosis = "链路已通，但服务端返回了业务或网关异常";
    }

    networkStatusView.setText(String.format(
      Locale.getDefault(),
      "%s\n%s\n%s",
      endpointLine,
      resultLine,
      diagnosis
    ));
  }

  @NonNull
  private String eventsEndpointUrl() {
    String base = BuildConfig.DRIVESERVER_BASE_URL;
    if (base.endsWith("/")) {
      return base + "api/v1/events";
    }
    return base + "/api/v1/events";
  }

  @NonNull
  private EdgeEvent buildProbeEvent() {
    long nowMs = System.currentTimeMillis();
    return new EdgeEvent(
      "evt_probe_" + nowMs,
      "fleet-local",
      "VEH-LOCAL-001",
      "DRV-LOCAL-001",
      Instant.ofEpochMilli(nowMs).toString(),
      0.72,
      0.28,
      RiskLevel.LOW,
      null,
      Collections.emptySet(),
      "edge-probe-v1",
      UploadStatus.PENDING,
      nowMs - 3_000L,
      nowMs,
      nowMs
    );
  }

  private static final class NetworkProbeResult {
    @NonNull
    final String endpoint;
    final int httpStatus;
    @Nullable
    final Integer businessCode;
    @Nullable
    final String traceId;
    @Nullable
    final String errorMessage;

    private NetworkProbeResult(
      @NonNull String endpoint,
      int httpStatus,
      @Nullable Integer businessCode,
      @Nullable String traceId,
      @Nullable String errorMessage
    ) {
      this.endpoint = endpoint;
      this.httpStatus = httpStatus;
      this.businessCode = businessCode;
      this.traceId = traceId;
      this.errorMessage = errorMessage;
    }

    @NonNull
    static NetworkProbeResult response(
      @NonNull String endpoint,
      int httpStatus,
      @Nullable Integer businessCode,
      @Nullable String traceId
    ) {
      return new NetworkProbeResult(endpoint, httpStatus, businessCode, traceId, null);
    }

    @NonNull
    static NetworkProbeResult failure(@NonNull String endpoint, @NonNull String errorMessage) {
      return new NetworkProbeResult(endpoint, 0, null, null, errorMessage);
    }
  }

  private void ensurePermissionsThenStart() {
    List<String> permissions = new ArrayList<>();
    permissions.add(Manifest.permission.CAMERA);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    }

    List<String> missingPermissions = new ArrayList<>();
    for (String permission : permissions) {
      if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
        missingPermissions.add(permission);
      }
    }

    if (missingPermissions.isEmpty()) {
      startCaptureAndBindService();
      return;
    }

    permissionLauncher.launch(missingPermissions.toArray(new String[0]));
  }

  private void startCaptureAndBindService() {
    CameraForegroundService.start(this);
    if (isBound) {
      return;
    }

    Intent bindIntent = new Intent(this, CameraForegroundService.class);
    bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  private void stopCaptureService() {
    if (isBound) {
      captureService.setFrameListener(null);
      captureService.detachPreview();
      unbindService(serviceConnection);
      captureService = null;
      isBound = false;
    }

    CameraForegroundService.stop(this);
    statusView.setText(getString(R.string.status_stopped));
    startButton.setEnabled(true);
    stopButton.setEnabled(false);
  }
}
