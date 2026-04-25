package com.driveedge.app.event;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.driveedge.storage.EdgeEventRow;
import com.driveedge.storage.StorageCenter;
import com.driveedge.storage.UploadAttemptResult;
import com.driveedge.storage.UploadFailureClass;
import com.driveedge.storage.UploadQueueItem;
import com.driveedge.uploader.EventUploader;
import com.driveedge.uploader.UploadReceipt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class EdgeEventUploadWorker extends Worker {
  static final String UNIQUE_WORK_NAME = "driveedge-edge-upload";
  static final String PERIODIC_WORK_NAME = "driveedge-edge-upload-periodic";
  private static final int UPLOAD_BATCH_SIZE = 8;
  private static final int MAX_UPLOAD_BATCH_SIZE = 24;

  public EdgeEventUploadWorker(
    @NonNull Context appContext,
    @NonNull WorkerParameters workerParams
  ) {
    super(appContext, workerParams);
  }

  @NonNull
  @Override
  public Result doWork() {
    Context appContext = getApplicationContext();
    EdgeEventReporter.ReporterQueueStore store = EdgeEventReporter.createQueueStore(appContext);
    StorageCenter storageCenter = EdgeEventReporter.createStorageCenter(store);
    EventUploader eventUploader = EdgeEventReporter.createEventUploader();

    try {
      processUploads(store, storageCenter, eventUploader, appContext);
      return Result.success();
    } catch (Exception error) {
      return Result.retry();
    }
  }

  static void enqueueImmediate(@NonNull Context context) {
    WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
      UNIQUE_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      buildOneTimeRequest(0L)
    );
  }

  static void enqueueDelayed(@NonNull Context context, long delayMs) {
    WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
      UNIQUE_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      buildOneTimeRequest(Math.max(0L, delayMs))
    );
  }

  static void ensurePeriodic(@NonNull Context context) {
    PeriodicWorkRequest request =
      new PeriodicWorkRequest.Builder(EdgeEventUploadWorker.class, 15, TimeUnit.MINUTES)
        .setConstraints(networkConstraints())
        .build();
    WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
      PERIODIC_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      request
    );
  }

  @NonNull
  private static OneTimeWorkRequest buildOneTimeRequest(long delayMs) {
    return new OneTimeWorkRequest.Builder(EdgeEventUploadWorker.class)
      .setConstraints(networkConstraints())
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .build();
  }

  @NonNull
  private static Constraints networkConstraints() {
    return new Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build();
  }

  private static void processUploads(
    @NonNull EdgeEventReporter.ReporterQueueStore store,
    @NonNull StorageCenter storageCenter,
    @NonNull EventUploader eventUploader,
    @NonNull Context context
  ) {
    while (true) {
      long nowMs = System.currentTimeMillis();
      if (!isNetworkAvailable(context)) {
        return;
      }

      List<UploadQueueItem> batch = storageCenter.claimUploadBatch(resolveBatchSize(store, nowMs), nowMs);
      if (batch.isEmpty()) {
        Long nextRetryAtMs = store.nextRetryAtMs(nowMs);
        if (nextRetryAtMs != null && nextRetryAtMs > nowMs) {
          enqueueDelayed(context, nextRetryAtMs - nowMs);
        }
        return;
      }

      for (UploadQueueItem item : batch) {
        UploadReceipt receipt = eventUploader.upload(item.getEvent());
        EdgeEventRow row = storageCenter.onUploadResult(
          new UploadAttemptResult(
            item.getEvent().getEventId(),
            receipt.getCode(),
            firstNonBlank(receipt.getTransportError(), receipt.getMessage(), null),
            receipt.getTraceId(),
            toFailureClass(receipt)
          ),
          System.currentTimeMillis()
        );
        if (row != null && row.getUploadStatus() == com.driveedge.event.center.UploadStatus.RETRY_WAIT) {
          Long nextRetryAtMs = row.getNextRetryAtMs();
          long currentMs = System.currentTimeMillis();
          if (nextRetryAtMs != null && nextRetryAtMs > currentMs) {
            enqueueDelayed(context, nextRetryAtMs - currentMs);
          }
        }
      }
    }
  }

  private static int resolveBatchSize(
    @NonNull EdgeEventReporter.ReporterQueueStore store,
    long nowMs
  ) {
    int queued = store.countQueuedEvents(nowMs);
    if (queued <= UPLOAD_BATCH_SIZE) {
      return UPLOAD_BATCH_SIZE;
    }
    return Math.min(MAX_UPLOAD_BATCH_SIZE, Math.max(UPLOAD_BATCH_SIZE, queued / 2));
  }

  @NonNull
  private static UploadFailureClass toFailureClass(@NonNull UploadReceipt receipt) {
    switch (receipt.getFailureCategory()) {
      case NETWORK:
        return UploadFailureClass.NETWORK;
      case TIMEOUT:
        return UploadFailureClass.TIMEOUT;
      case SERVER:
        return UploadFailureClass.SERVER;
      case CLIENT:
        return UploadFailureClass.CLIENT;
      case RESPONSE_PARSE:
        return UploadFailureClass.RESPONSE_PARSE;
      case UNKNOWN:
        return UploadFailureClass.UNKNOWN;
      case NONE:
      default:
        return UploadFailureClass.NONE;
    }
  }

  private static boolean isNetworkAvailable(@NonNull Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    if (manager == null) {
      return true;
    }
    try {
      Network activeNetwork = manager.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
      if (capabilities == null) {
        return false;
      }
      return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    } catch (Exception ignored) {
      return true;
    }
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    if (first != null && !first.trim().isEmpty()) {
      return first;
    }
    if (second != null && !second.trim().isEmpty()) {
      return second;
    }
    return fallback;
  }
}
