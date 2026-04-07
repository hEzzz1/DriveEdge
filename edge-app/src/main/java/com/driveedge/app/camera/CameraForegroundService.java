package com.driveedge.app.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.driveedge.app.R;
import com.driveedge.app.ui.MainActivity;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraForegroundService extends LifecycleService {

  public interface FrameListener {
    void onFrame(FrameData frameData);
  }

  public final class LocalBinder extends Binder {
    public CameraForegroundService getService() {
      return CameraForegroundService.this;
    }
  }

  public static final String ACTION_START = "com.driveedge.app.camera.action.START";
  public static final String ACTION_STOP = "com.driveedge.app.camera.action.STOP";

  private static final String CHANNEL_ID = "driveedge.camera.capture";
  private static final int NOTIFICATION_ID = 1001;

  private final LocalBinder localBinder = new LocalBinder();
  private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

  @Nullable
  private ProcessCameraProvider cameraProvider;

  @Nullable
  private Preview.SurfaceProvider previewSurfaceProvider;

  @Nullable
  private volatile FrameListener frameListener;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
    startForeground(NOTIFICATION_ID, buildNotification());
    initCameraProvider();
  }

  @NonNull
  @Override
  public IBinder onBind(Intent intent) {
    super.onBind(intent);
    return localBinder;
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    super.onStartCommand(intent, flags, startId);
    String action = intent == null ? null : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      stopForeground(Service.STOP_FOREGROUND_REMOVE);
      stopSelf();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    if (cameraProvider != null) {
      cameraProvider.unbindAll();
    }
    analyzerExecutor.shutdown();
    super.onDestroy();
  }

  public void attachPreview(@NonNull Preview.SurfaceProvider surfaceProvider) {
    previewSurfaceProvider = surfaceProvider;
    bindUseCasesIfReady();
  }

  public void detachPreview() {
    previewSurfaceProvider = null;
    bindUseCasesIfReady();
  }

  public void setFrameListener(@Nullable FrameListener listener) {
    frameListener = listener;
  }

  private void initCameraProvider() {
    ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
    providerFuture.addListener(
      () -> {
        try {
          cameraProvider = providerFuture.get();
          bindUseCasesIfReady();
        } catch (Exception ignored) {
          // Keep service alive; UI status will remain disconnected.
        }
      },
      ContextCompat.getMainExecutor(this)
    );
  }

  private void bindUseCasesIfReady() {
    ProcessCameraProvider provider = cameraProvider;
    if (provider == null) {
      return;
    }

    provider.unbindAll();

    Preview preview = new Preview.Builder().build();
    ImageAnalysis analysis = new ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build();

    Preview.SurfaceProvider previewProvider = previewSurfaceProvider;
    if (previewProvider != null) {
      preview.setSurfaceProvider(previewProvider);
    }

    analysis.setAnalyzer(analyzerExecutor, image -> {
      FrameListener listener = frameListener;
      if (listener == null) {
        image.close();
        return;
      }

      FrameData frameData = new FrameData(
        image.getWidth(),
        image.getHeight(),
        image.getImageInfo().getRotationDegrees(),
        image.getImageInfo().getTimestamp(),
        toNv21(image)
      );
      image.close();

      listener.onFrame(frameData);
    });

    if (previewProvider == null) {
      provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
      return;
    }

    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
  }

  private Notification buildNotification() {
    Intent openAppIntent = new Intent(this, MainActivity.class);
    PendingIntent pendingIntent = PendingIntent.getActivity(
      this,
      0,
      openAppIntent,
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    );

    return new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_camera_service)
      .setContentTitle(getString(R.string.notification_title))
      .setContentText(getString(R.string.notification_text))
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .setSilent(true)
      .build();
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    NotificationChannel channel = new NotificationChannel(
      CHANNEL_ID,
      getString(R.string.notification_channel_name),
      NotificationManager.IMPORTANCE_LOW
    );

    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.createNotificationChannel(channel);
    }
  }

  private byte[] toNv21(@NonNull ImageProxy image) {
    ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
    ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
    ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];

    int width = image.getWidth();
    int height = image.getHeight();
    byte[] nv21 = new byte[width * height + (width * height / 2)];

    copyPlane(yPlane.getBuffer(), yPlane.getRowStride(), yPlane.getPixelStride(), width, height, nv21, 0);

    int outputIndex = width * height;
    int chromaHeight = height / 2;
    int chromaWidth = width / 2;

    ByteBuffer uBuffer = uPlane.getBuffer();
    ByteBuffer vBuffer = vPlane.getBuffer();

    for (int row = 0; row < chromaHeight; row++) {
      int uRowStart = row * uPlane.getRowStride();
      int vRowStart = row * vPlane.getRowStride();
      for (int col = 0; col < chromaWidth; col++) {
        nv21[outputIndex++] = vBuffer.get(vRowStart + col * vPlane.getPixelStride());
        nv21[outputIndex++] = uBuffer.get(uRowStart + col * uPlane.getPixelStride());
      }
    }

    return nv21;
  }

  private void copyPlane(
    @NonNull ByteBuffer buffer,
    int rowStride,
    int pixelStride,
    int width,
    int height,
    @NonNull byte[] out,
    int outOffset
  ) {
    int outputIndex = outOffset;
    for (int row = 0; row < height; row++) {
      int rowStart = row * rowStride;
      for (int col = 0; col < width; col++) {
        out[outputIndex++] = buffer.get(rowStart + col * pixelStride);
      }
    }
  }

  public static void start(@NonNull Context context) {
    Intent intent = new Intent(context, CameraForegroundService.class);
    intent.setAction(ACTION_START);
    ContextCompat.startForegroundService(context, intent);
  }

  public static void stop(@NonNull Context context) {
    Intent intent = new Intent(context, CameraForegroundService.class);
    intent.setAction(ACTION_STOP);
    context.startService(intent);
  }
}
