package com.driveedge.app.event;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.driveedge.event.center.EdgeEvent;
import com.driveedge.event.center.UploadStatus;
import com.driveedge.risk.engine.RiskLevel;
import com.driveedge.risk.engine.RiskType;
import com.driveedge.risk.engine.TriggerReason;
import com.driveedge.storage.DeviceConfigRow;
import com.driveedge.storage.EdgeEventRow;
import com.driveedge.storage.UploadFailureClass;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SQLiteOutboxStore extends SQLiteOpenHelper implements EdgeEventReporter.ReporterQueueStore {
  private static final String DB_NAME = "driveedge_outbox.db";
  private static final int DB_VERSION = 1;

  private static final String TABLE_OUTBOX = "edge_event_outbox";
  private static final String TABLE_DEVICE_CONFIG = "device_config";

  SQLiteOutboxStore(@NonNull Context context) {
    super(context, DB_NAME, null, DB_VERSION);
  }

  @Override
  public void onCreate(@NonNull SQLiteDatabase db) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS " + TABLE_OUTBOX + " ("
        + "event_id TEXT PRIMARY KEY NOT NULL,"
        + "fleet_id TEXT,"
        + "vehicle_id TEXT NOT NULL,"
        + "driver_id TEXT,"
        + "event_time_utc TEXT NOT NULL,"
        + "fatigue_score REAL NOT NULL,"
        + "distraction_score REAL NOT NULL,"
        + "risk_level TEXT NOT NULL,"
        + "dominant_risk_type TEXT,"
        + "trigger_reasons_json TEXT NOT NULL,"
        + "algorithm_ver TEXT NOT NULL,"
        + "upload_status TEXT NOT NULL,"
        + "window_start_ms INTEGER NOT NULL,"
        + "window_end_ms INTEGER NOT NULL,"
        + "created_at_ms INTEGER NOT NULL,"
        + "retry_count INTEGER NOT NULL,"
        + "last_error_code INTEGER,"
        + "last_error_message TEXT,"
        + "server_trace_id TEXT,"
        + "next_retry_at_ms INTEGER,"
        + "last_attempt_at_ms INTEGER,"
        + "failure_class TEXT NOT NULL,"
        + "updated_at_ms INTEGER NOT NULL"
        + ")"
    );
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_outbox_ready "
        + "ON " + TABLE_OUTBOX + " (upload_status, next_retry_at_ms, last_attempt_at_ms, created_at_ms)"
    );
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS " + TABLE_DEVICE_CONFIG + " ("
        + "device_id TEXT PRIMARY KEY NOT NULL,"
        + "fleet_id TEXT,"
        + "vehicle_id TEXT NOT NULL,"
        + "model_profile TEXT NOT NULL,"
        + "threshold_profile TEXT NOT NULL,"
        + "upload_policy TEXT NOT NULL,"
        + "updated_at_ms INTEGER NOT NULL"
        + ")"
    );
  }

  @Override
  public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
    // No-op for v1.
  }

  @Override
  public synchronized void upsert(@NonNull EdgeEventRow row) {
    getWritableDatabase().insertWithOnConflict(
      TABLE_OUTBOX,
      null,
      toValues(row),
      SQLiteDatabase.CONFLICT_REPLACE
    );
  }

  @Override
  public synchronized EdgeEventRow getByEventId(String eventId) {
    try (
      Cursor cursor =
        getReadableDatabase().query(
          TABLE_OUTBOX,
          null,
          "event_id = ?",
          new String[] {eventId},
          null,
          null,
          null,
          "1"
        )
    ) {
      if (!cursor.moveToFirst()) {
        return null;
      }
      return fromCursor(cursor);
    }
  }

  @Override
  public synchronized void update(@NonNull EdgeEventRow row) {
    getWritableDatabase().update(
      TABLE_OUTBOX,
      toValues(row),
      "event_id = ?",
      new String[] {row.getEventId()}
    );
  }

  @Override
  public synchronized List<EdgeEventRow> listReadyForUpload(long nowMs, int limit) {
    List<EdgeEventRow> rows = new ArrayList<>();
    String selection =
      "(upload_status = ?)"
        + " OR (upload_status = ? AND next_retry_at_ms IS NOT NULL AND next_retry_at_ms <= ?)";
    String orderBy =
      "CASE WHEN upload_status = 'RETRY_WAIT' THEN 0 WHEN upload_status = 'PENDING' THEN 1 ELSE 2 END, "
        + "COALESCE(next_retry_at_ms, -9223372036854775808), "
        + "COALESCE(last_attempt_at_ms, -9223372036854775808), "
        + "created_at_ms";
    try (
      Cursor cursor =
        getReadableDatabase().query(
          TABLE_OUTBOX,
          null,
          selection,
          new String[] {UploadStatus.PENDING.name(), UploadStatus.RETRY_WAIT.name(), String.valueOf(nowMs)},
          null,
          null,
          orderBy,
          String.valueOf(Math.max(0, limit))
        )
    ) {
      while (cursor.moveToNext()) {
        rows.add(fromCursor(cursor));
      }
    }
    return rows;
  }

  @Override
  public synchronized void upsert(@NonNull DeviceConfigRow row) {
    getWritableDatabase().insertWithOnConflict(
      TABLE_DEVICE_CONFIG,
      null,
      toValues(row),
      SQLiteDatabase.CONFLICT_REPLACE
    );
  }

  @Override
  public synchronized DeviceConfigRow getByDeviceId(String deviceId) {
    try (
      Cursor cursor =
        getReadableDatabase().query(
          TABLE_DEVICE_CONFIG,
          null,
          "device_id = ?",
          new String[] {deviceId},
          null,
          null,
          null,
          "1"
        )
    ) {
      if (!cursor.moveToFirst()) {
        return null;
      }
      return new DeviceConfigRow(
        nullableString(cursor, "fleet_id"),
        requiredString(cursor, "vehicle_id"),
        requiredString(cursor, "device_id"),
        requiredString(cursor, "model_profile"),
        requiredString(cursor, "threshold_profile"),
        requiredString(cursor, "upload_policy"),
        cursor.getLong(column(cursor, "updated_at_ms"))
      );
    }
  }

  @Override
  public synchronized int countQueuedEvents(long nowMs) {
    try (
      Cursor cursor =
        getReadableDatabase().rawQuery(
          "SELECT COUNT(*) FROM " + TABLE_OUTBOX + " WHERE upload_status IN (?, ?, ?)",
          new String[] {
            UploadStatus.PENDING.name(),
            UploadStatus.RETRY_WAIT.name(),
            UploadStatus.SENDING.name(),
          }
        )
    ) {
      if (!cursor.moveToFirst()) {
        return 0;
      }
      return cursor.getInt(0);
    }
  }

  @Override
  public synchronized boolean hasReadyEvents(long nowMs) {
    return !listReadyForUpload(nowMs, 1).isEmpty();
  }

  @Nullable
  @Override
  public synchronized Long nextRetryAtMs(long nowMs) {
    try (
      Cursor cursor =
        getReadableDatabase().rawQuery(
          "SELECT MIN(next_retry_at_ms) FROM " + TABLE_OUTBOX
            + " WHERE upload_status = ? AND next_retry_at_ms IS NOT NULL",
          new String[] {UploadStatus.RETRY_WAIT.name()}
        )
    ) {
      if (!cursor.moveToFirst() || cursor.isNull(0)) {
        return null;
      }
      long nextRetryAtMs = cursor.getLong(0);
      return nextRetryAtMs <= nowMs ? nowMs : nextRetryAtMs;
    }
  }

  @NonNull
  private ContentValues toValues(@NonNull EdgeEventRow row) {
    EdgeEvent event = row.getEvent();
    ContentValues values = new ContentValues();
    values.put("event_id", row.getEventId());
    values.put("fleet_id", event.getFleetId());
    values.put("vehicle_id", event.getVehicleId());
    values.put("driver_id", event.getDriverId());
    values.put("event_time_utc", event.getEventTimeUtc());
    values.put("fatigue_score", event.getFatigueScore());
    values.put("distraction_score", event.getDistractionScore());
    values.put("risk_level", event.getRiskLevel().name());
    values.put("dominant_risk_type", event.getDominantRiskType() == null ? null : event.getDominantRiskType().name());
    values.put("trigger_reasons_json", encodeTriggerReasons(event.getTriggerReasons()));
    values.put("algorithm_ver", event.getAlgorithmVer());
    values.put("upload_status", normalizePersistedStatus(row.getUploadStatus()).name());
    values.put("window_start_ms", event.getWindowStartMs());
    values.put("window_end_ms", event.getWindowEndMs());
    values.put("created_at_ms", event.getCreatedAtMs());
    values.put("retry_count", row.getRetryCount());
    if (row.getLastErrorCode() == null) {
      values.putNull("last_error_code");
    } else {
      values.put("last_error_code", row.getLastErrorCode());
    }
    values.put("last_error_message", row.getLastErrorMessage());
    values.put("server_trace_id", row.getServerTraceId());
    if (row.getNextRetryAtMs() == null) {
      values.putNull("next_retry_at_ms");
    } else {
      values.put("next_retry_at_ms", row.getNextRetryAtMs());
    }
    if (row.getLastAttemptAtMs() == null) {
      values.putNull("last_attempt_at_ms");
    } else {
      values.put("last_attempt_at_ms", row.getLastAttemptAtMs());
    }
    values.put("failure_class", row.getFailureClass().name());
    values.put("updated_at_ms", row.getUpdatedAtMs());
    return values;
  }

  @NonNull
  private ContentValues toValues(@NonNull DeviceConfigRow row) {
    ContentValues values = new ContentValues();
    values.put("fleet_id", row.getFleetId());
    values.put("vehicle_id", row.getVehicleId());
    values.put("device_id", row.getDeviceId());
    values.put("model_profile", row.getModelProfile());
    values.put("threshold_profile", row.getThresholdProfile());
    values.put("upload_policy", row.getUploadPolicy());
    values.put("updated_at_ms", row.getUpdatedAtMs());
    return values;
  }

  @NonNull
  private EdgeEventRow fromCursor(@NonNull Cursor cursor) {
    RiskType dominantRiskType = null;
    String dominantRiskTypeName = nullableString(cursor, "dominant_risk_type");
    if (dominantRiskTypeName != null && !dominantRiskTypeName.trim().isEmpty()) {
      try {
        dominantRiskType = RiskType.valueOf(dominantRiskTypeName);
      } catch (IllegalArgumentException ignored) {
      }
    }

    UploadStatus uploadStatus = UploadStatus.valueOf(requiredString(cursor, "upload_status"));
    if (uploadStatus == UploadStatus.SENDING) {
      uploadStatus = UploadStatus.RETRY_WAIT;
    }

    UploadFailureClass failureClass = UploadFailureClass.NONE;
    String failureClassName = requiredString(cursor, "failure_class");
    try {
      failureClass = UploadFailureClass.valueOf(failureClassName);
    } catch (IllegalArgumentException ignored) {
    }

    EdgeEvent event =
      new EdgeEvent(
        requiredString(cursor, "event_id"),
        nullableString(cursor, "fleet_id"),
        requiredString(cursor, "vehicle_id"),
        nullableString(cursor, "driver_id"),
        requiredString(cursor, "event_time_utc"),
        cursor.getDouble(column(cursor, "fatigue_score")),
        cursor.getDouble(column(cursor, "distraction_score")),
        RiskLevel.valueOf(requiredString(cursor, "risk_level")),
        dominantRiskType,
        decodeTriggerReasons(requiredString(cursor, "trigger_reasons_json")),
        requiredString(cursor, "algorithm_ver"),
        uploadStatus,
        cursor.getLong(column(cursor, "window_start_ms")),
        cursor.getLong(column(cursor, "window_end_ms")),
        cursor.getLong(column(cursor, "created_at_ms"))
      );

    return new EdgeEventRow(
      event,
      uploadStatus,
      cursor.getInt(column(cursor, "retry_count")),
      cursor.isNull(column(cursor, "last_error_code")) ? null : cursor.getInt(column(cursor, "last_error_code")),
      nullableString(cursor, "last_error_message"),
      nullableString(cursor, "server_trace_id"),
      cursor.isNull(column(cursor, "next_retry_at_ms")) ? null : cursor.getLong(column(cursor, "next_retry_at_ms")),
      cursor.isNull(column(cursor, "last_attempt_at_ms")) ? null : cursor.getLong(column(cursor, "last_attempt_at_ms")),
      failureClass,
      cursor.getLong(column(cursor, "updated_at_ms"))
    );
  }

  @NonNull
  private String encodeTriggerReasons(@NonNull Set<TriggerReason> triggerReasons) {
    JSONArray array = new JSONArray();
    for (TriggerReason reason : triggerReasons) {
      array.put(reason.name());
    }
    return array.toString();
  }

  @NonNull
  private Set<TriggerReason> decodeTriggerReasons(@NonNull String encoded) {
    Set<TriggerReason> triggerReasons = new LinkedHashSet<>();
    try {
      JSONArray array = new JSONArray(encoded);
      for (int index = 0; index < array.length(); index++) {
        String reasonName = array.optString(index, null);
        if (reasonName == null || reasonName.trim().isEmpty()) {
          continue;
        }
        try {
          triggerReasons.add(TriggerReason.valueOf(reasonName));
        } catch (IllegalArgumentException ignored) {
        }
      }
    } catch (JSONException ignored) {
    }
    return triggerReasons;
  }

  @NonNull
  private UploadStatus normalizePersistedStatus(@NonNull UploadStatus uploadStatus) {
    return uploadStatus == UploadStatus.SENDING ? UploadStatus.RETRY_WAIT : uploadStatus;
  }

  private int column(@NonNull Cursor cursor, @NonNull String name) {
    return cursor.getColumnIndexOrThrow(name);
  }

  @NonNull
  private String requiredString(@NonNull Cursor cursor, @NonNull String name) {
    int index = column(cursor, name);
    return cursor.isNull(index) ? "" : cursor.getString(index);
  }

  @Nullable
  private String nullableString(@NonNull Cursor cursor, @NonNull String name) {
    int index = column(cursor, name);
    return cursor.isNull(index) ? null : cursor.getString(index);
  }
}
