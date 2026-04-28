package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.driveedge.app.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class EdgeApiClient {
  private static final int SUCCESS_CODE = 0;

  @NonNull
  public EdgeLocalContext activate(@NonNull EdgeLocalContext baseContext) throws Exception {
    JSONObject request = new JSONObject();
    request.put("deviceCode", BuildConfig.EDGE_DEVICE_CODE);
    request.put("activationCode", BuildConfig.EDGE_ACTIVATION_CODE);
    JSONObject data = request("/api/v1/edge/device/activate", "POST", null, null, request);
    EdgeLocalContext context = baseContext.copy();
    context.deviceId = readLong(data, "deviceId");
    context.deviceCode = readString(data, "deviceCode");
    context.deviceName = readString(data, "deviceName");
    context.deviceToken = readString(data, "deviceToken");
    context.enterpriseId = readLong(data, "enterpriseId");
    context.fleetId = readLong(data, "fleetId");
    context.vehicleId = readLong(data, "vehicleId");
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  @NonNull
  public EdgeLocalContext fetchContext(@NonNull EdgeLocalContext baseContext) throws Exception {
    JSONObject data = request("/api/v1/edge/device/context", "GET", required(baseContext.deviceCode), required(baseContext.deviceToken), null);
    EdgeLocalContext context = baseContext.copy();
    context.deviceId = readLong(data, "deviceId");
    context.deviceCode = readString(data, "deviceCode");
    context.deviceName = readString(data, "deviceName");
    context.enterpriseId = readLong(data, "enterpriseId");
    context.enterpriseName = readString(data, "enterpriseName");
    context.fleetId = readLong(data, "fleetId");
    context.fleetName = readString(data, "fleetName");
    context.vehicleId = readLong(data, "vehicleId");
    context.vehiclePlateNumber = readString(data, "vehiclePlateNumber");
    context.driverId = readLong(data, "currentDriverId");
    context.driverCode = readString(data, "currentDriverCode");
    context.driverName = readString(data, "currentDriverName");
    context.sessionId = readLong(data, "currentSessionId");
    context.sessionNo = readString(data, "currentSessionNo");
    context.signedInAt = readString(data, "currentSessionSignInTime");
    Integer sessionStatus = readInt(data, "currentSessionStatus");
    context.sessionStatus = sessionStatus == null ? null : sessionStatus.byteValue();
    context.configVersion = readString(data, "configVersion");
    context.lastSyncAt = Instant.now().toString();
    if (context.sessionId == null) {
      context.clearSession();
    }
    return context;
  }

  @NonNull
  public EdgeLocalContext fetchCurrentSession(@NonNull EdgeLocalContext baseContext) throws Exception {
    JSONObject data = request("/api/v1/edge/sessions/current", "GET", required(baseContext.deviceCode), required(baseContext.deviceToken), null);
    return mergeSession(baseContext, data);
  }

  @NonNull
  public EdgeLocalContext signIn(@NonNull EdgeLocalContext baseContext, @NonNull String driverCode, @NonNull String pin) throws Exception {
    JSONObject request = new JSONObject();
    request.put("driverCode", driverCode.trim());
    request.put("pin", pin);
    JSONObject data = request("/api/v1/edge/sessions/sign-in", "POST", required(baseContext.deviceCode), required(baseContext.deviceToken), request);
    return mergeSession(baseContext, data);
  }

  @NonNull
  public EdgeLocalContext signOut(@NonNull EdgeLocalContext baseContext, @Nullable String remark) throws Exception {
    JSONObject request = new JSONObject();
    if (remark != null && !remark.trim().isEmpty()) {
      request.put("remark", remark.trim());
    }
    JSONObject data = request("/api/v1/edge/sessions/sign-out", "POST", required(baseContext.deviceCode), required(baseContext.deviceToken), request);
    EdgeLocalContext context = mergeSession(baseContext, data);
    context.clearSession();
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  @NonNull
  private EdgeLocalContext mergeSession(@NonNull EdgeLocalContext baseContext, @NonNull JSONObject data) {
    EdgeLocalContext context = baseContext.copy();
    context.enterpriseId = readLong(data, "enterpriseId");
    context.enterpriseName = readString(data, "enterpriseName");
    context.fleetId = readLong(data, "fleetId");
    context.fleetName = readString(data, "fleetName");
    context.vehicleId = readLong(data, "vehicleId");
    context.vehiclePlateNumber = readString(data, "vehiclePlateNumber");
    context.driverId = readLong(data, "driverId");
    context.driverCode = readString(data, "driverCode");
    context.driverName = readString(data, "driverName");
    context.sessionId = readLong(data, "sessionId");
    context.sessionNo = readString(data, "sessionNo");
    Integer sessionStatus = readInt(data, "status");
    context.sessionStatus = sessionStatus == null ? null : sessionStatus.byteValue();
    context.signedInAt = readString(data, "signInTime");
    context.sessionClosedReason = readString(data, "closedReason");
    context.configVersion = readString(data, "configVersion");
    context.lastSyncAt = Instant.now().toString();
    if (context.sessionId == null || context.sessionStatus == null || context.sessionStatus != (byte) 1) {
      context.clearSession();
    }
    return context;
  }

  @NonNull
  private JSONObject request(
    @NonNull String path,
    @NonNull String method,
    @Nullable String deviceCode,
    @Nullable String deviceToken,
    @Nullable JSONObject body
  ) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.EDGE_SERVER_BASE_URL + path).openConnection();
    try {
      connection.setRequestMethod(method);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(8000);
      connection.setRequestProperty("Accept", "application/json");
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
      }
      if (deviceCode != null) {
        connection.setRequestProperty("X-Device-Code", deviceCode);
      }
      if (deviceToken != null) {
        connection.setRequestProperty("X-Device-Token", deviceToken);
      }
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
          output.write(bytes);
          output.flush();
        }
      }
      int status = connection.getResponseCode();
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String payload = readBody(stream);
      JSONObject root = payload == null || payload.trim().isEmpty() ? new JSONObject() : new JSONObject(payload);
      int code = root.optInt("code", status);
      if (code != SUCCESS_CODE) {
        throw new EdgeApiException(code, root.optString("message", "request failed"), root.optString("traceId", null));
      }
      JSONObject data = root.optJSONObject("data");
      return data == null ? new JSONObject() : data;
    } finally {
      connection.disconnect();
    }
  }

  private String required(@Nullable String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException("device identity missing");
    }
    return value;
  }

  @Nullable
  private String readBody(@Nullable InputStream stream) throws Exception {
    if (stream == null) {
      return null;
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      StringBuilder builder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        builder.append(line);
      }
      return builder.toString();
    }
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
