package com.driveedge.app.edge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONException;
import org.json.JSONObject;

public final class EdgeContextStore {
  private static final String PREFS_NAME = "driveedge_secure_context";
  private static final String KEY_CONTEXT = "edge_local_context";

  @NonNull
  private final SharedPreferences preferences;

  public EdgeContextStore(@NonNull Context context) {
    this.preferences = createPreferences(context.getApplicationContext());
  }

  @NonNull
  public synchronized EdgeLocalContext load() {
    String encoded = preferences.getString(KEY_CONTEXT, null);
    if (encoded == null || encoded.trim().isEmpty()) {
      return EdgeLocalContext.empty();
    }
    try {
      return decode(new JSONObject(encoded));
    } catch (JSONException error) {
      return EdgeLocalContext.empty();
    }
  }

  public synchronized void save(@NonNull EdgeLocalContext context) {
    preferences.edit().putString(KEY_CONTEXT, encode(context).toString()).apply();
  }

  public synchronized void clearSession() {
    EdgeLocalContext context = load();
    context.clearSession();
    save(context);
  }

  @NonNull
  private SharedPreferences createPreferences(@NonNull Context context) {
    try {
      MasterKey masterKey =
        new MasterKey.Builder(context)
          .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
          .build();
      return EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
      );
    } catch (Exception ignored) {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
  }

  @NonNull
  private JSONObject encode(@NonNull EdgeLocalContext context) {
    JSONObject json = new JSONObject();
    try {
      json.put("deviceId", context.deviceId);
      json.put("deviceCode", context.deviceCode);
      json.put("deviceName", context.deviceName);
      json.put("deviceToken", context.deviceToken);
      json.put("enterpriseId", context.enterpriseId);
      json.put("enterpriseName", context.enterpriseName);
      json.put("fleetId", context.fleetId);
      json.put("fleetName", context.fleetName);
      json.put("vehicleId", context.vehicleId);
      json.put("vehiclePlateNumber", context.vehiclePlateNumber);
      json.put("driverId", context.driverId);
      json.put("driverCode", context.driverCode);
      json.put("driverName", context.driverName);
      json.put("sessionId", context.sessionId);
      json.put("sessionNo", context.sessionNo);
      json.put("sessionStatus", context.sessionStatus == null ? JSONObject.NULL : context.sessionStatus.intValue());
      json.put("configVersion", context.configVersion);
      json.put("signedInAt", context.signedInAt);
      json.put("lastSyncAt", context.lastSyncAt);
      json.put("sessionClosedReason", context.sessionClosedReason);
    } catch (JSONException ignored) {
    }
    return json;
  }

  @NonNull
  private EdgeLocalContext decode(@NonNull JSONObject json) {
    EdgeLocalContext context = EdgeLocalContext.empty();
    context.deviceId = readLong(json, "deviceId");
    context.deviceCode = readString(json, "deviceCode");
    context.deviceName = readString(json, "deviceName");
    context.deviceToken = readString(json, "deviceToken");
    context.enterpriseId = readLong(json, "enterpriseId");
    context.enterpriseName = readString(json, "enterpriseName");
    context.fleetId = readLong(json, "fleetId");
    context.fleetName = readString(json, "fleetName");
    context.vehicleId = readLong(json, "vehicleId");
    context.vehiclePlateNumber = readString(json, "vehiclePlateNumber");
    context.driverId = readLong(json, "driverId");
    context.driverCode = readString(json, "driverCode");
    context.driverName = readString(json, "driverName");
    context.sessionId = readLong(json, "sessionId");
    Integer sessionStatus = readInt(json, "sessionStatus");
    context.sessionStatus = sessionStatus == null ? null : sessionStatus.byteValue();
    context.sessionNo = readString(json, "sessionNo");
    context.configVersion = readString(json, "configVersion");
    context.signedInAt = readString(json, "signedInAt");
    context.lastSyncAt = readString(json, "lastSyncAt");
    context.sessionClosedReason = readString(json, "sessionClosedReason");
    return context;
  }

  private Long readLong(@NonNull JSONObject json, @NonNull String key) {
    return json.isNull(key) ? null : json.optLong(key);
  }

  private Integer readInt(@NonNull JSONObject json, @NonNull String key) {
    return json.isNull(key) ? null : json.optInt(key);
  }

  private String readString(@NonNull JSONObject json, @NonNull String key) {
    return json.isNull(key) ? null : json.optString(key, null);
  }
}
