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

import com.driveedge.app.R;
import com.driveedge.app.camera.CameraForegroundService;
import com.driveedge.app.camera.FrameData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

  private PreviewView previewView;
  private TextView statusView;
  private Button startButton;
  private Button stopButton;

  @Nullable
  private CameraForegroundService captureService;

  private boolean isBound;
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
    startButton = findViewById(R.id.startButton);
    stopButton = findViewById(R.id.stopButton);

    startButton.setOnClickListener(view -> ensurePermissionsThenStart());
    stopButton.setOnClickListener(view -> stopCaptureService());

    stopButton.setEnabled(false);
  }

  @Override
  protected void onDestroy() {
    if (isBound) {
      captureService.setFrameListener(null);
      captureService.detachPreview();
      unbindService(serviceConnection);
      isBound = false;
    }
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
