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
  public EdgeLocalContext claimDevice(
    @NonNull EdgeLocalContext baseContext,
    @NonNull String enterpriseActivationCode,
    @Nullable String deviceName
  ) throws Exception {
    JSONObject request = new JSONObject();
    request.put("deviceCode", required(baseContext.deviceCode));
    if (deviceName != null && !deviceName.trim().isEmpty()) {
      request.put("deviceName", deviceName.trim());
    }
    request.put("enterpriseActivationCode", required(enterpriseActivationCode));

    JSONObject data = request("/api/v1/edge/device/claim", "POST", null, null, request);
    EdgeLocalContext context = baseContext.copy();
    clearContextSnapshot(context);
    mergeContextPayload(context, data);
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  @NonNull
  public EdgeLocalContext fetchContext(@NonNull EdgeLocalContext baseContext) throws Exception {
    JSONObject data = request(
      "/api/v1/edge/device/context",
      "GET",
      required(baseContext.deviceCode),
      required(baseContext.deviceToken),
      null
    );
    EdgeLocalContext context = baseContext.copy();
    clearContextSnapshot(context);
    mergeContextPayload(context, data);
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  @NonNull
  public EdgeLocalContext fetchCurrentSession(@NonNull EdgeLocalContext baseContext) throws Exception {
    JSONObject data = request(
      "/api/v1/edge/sessions/current",
      "GET",
      required(baseContext.deviceCode),
      required(baseContext.deviceToken),
      null
    );
    return mergeSession(baseContext, data);
  }

  @NonNull
  public EdgeLocalContext signIn(@NonNull EdgeLocalContext baseContext, @NonNull String driverCode, @NonNull String pin) throws Exception {
    JSONObject request = new JSONObject();
    request.put("driverCode", driverCode.trim());
    request.put("pin", pin);
    JSONObject data = request(
      "/api/v1/edge/sessions/sign-in",
      "POST",
      required(baseContext.deviceCode),
      required(baseContext.deviceToken),
      request
    );
    return mergeSession(baseContext, data);
  }

  @NonNull
  public EdgeLocalContext signOut(@NonNull EdgeLocalContext baseContext, @Nullable String remark) throws Exception {
    JSONObject request = new JSONObject();
    if (remark != null && !remark.trim().isEmpty()) {
      request.put("remark", remark.trim());
    }
    JSONObject data = request(
      "/api/v1/edge/sessions/sign-out",
      "POST",
      required(baseContext.deviceCode),
      required(baseContext.deviceToken),
      request
    );
    EdgeLocalContext context = mergeSession(baseContext, data);
    if (!isSessionActive(context)) {
      context.clearSession();
      context.sessionStage = "IDLE";
      context.effectiveStage = resolveNonSessionStage(context);
    }
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  @NonNull
  private EdgeLocalContext mergeSession(@NonNull EdgeLocalContext baseContext, @NonNull JSONObject data) {
    EdgeLocalContext context = baseContext.copy();
    clearSessionSnapshot(context);
    mergeSessionPayload(context, data);
    boolean sessionActive = isSessionActive(context);

    String effectiveStage = normalizeUpper(readString(data, "effectiveStage"));
    if (effectiveStage != null) {
      context.effectiveStage = effectiveStage;
    } else if (sessionActive) {
      context.effectiveStage = "IN_SESSION";
    } else if ("IN_SESSION".equals(context.effectiveStage)) {
      context.effectiveStage = resolveNonSessionStage(context);
    }

    context.configVersion = firstNonBlank(readString(data, "configVersion"), context.configVersion);
    context.lastSyncAt = Instant.now().toString();
    return context;
  }

  void mergeContextPayload(@NonNull EdgeLocalContext context, @NonNull JSONObject data) {
    JSONObject device = readObject(data, "device");
    if (device != null) {
      context.deviceId = firstNonNull(readLong(device, "id"), context.deviceId);
      context.deviceCode = firstNonBlank(readString(device, "deviceCode"), context.deviceCode);
      context.deviceName = firstNonBlank(readString(device, "deviceName"), context.deviceName);
      context.deviceToken = firstNonBlank(readString(device, "deviceToken"), context.deviceToken);
    }

    context.effectiveStage = normalizeUpper(readString(data, "effectiveStage"));
    context.enterpriseId = readNestedLong(data, "enterprise", "id");
    context.enterpriseName = readNestedString(data, "enterprise", "name");
    context.fleetId = readNestedLong(data, "fleet", "id");
    context.fleetName = readNestedString(data, "fleet", "name");
    context.vehicleId = readNestedLong(data, "vehicle", "id");
    context.vehiclePlateNumber = readNestedString(data, "vehicle", "plateNumber");
    context.configVersion = readString(data, "configVersion");

    clearSessionSnapshot(context);
    context.sessionStage = normalizeUpper(readString(data, "sessionStage"));
    mergeActiveSessionPayload(context, readObject(data, "activeSession"));
    if (context.sessionStage == null && context.sessionId != null) {
      context.sessionStage = "ACTIVE";
    }

    if (context.effectiveStage == null) {
      context.effectiveStage = context.sessionId != null ? "IN_SESSION" : resolveNonSessionStage(context);
    }
  }

  private void mergeActiveSessionPayload(@NonNull EdgeLocalContext context, @Nullable JSONObject activeSession) {
    if (activeSession == null) {
      return;
    }
    context.sessionId = firstNonNull(readLong(activeSession, "id"), readLong(activeSession, "sessionId"));
    context.sessionNo = readString(activeSession, "sessionNo");
    context.signedInAt = firstNonBlank(readString(activeSession, "signInTime"), readString(activeSession, "signedInAt"));
    context.sessionClosedReason = readString(activeSession, "closedReason");
    context.sessionStage = firstNonBlank(
      normalizeUpper(readString(activeSession, "stage")),
      normalizeUpper(readString(activeSession, "sessionStage"))
    );
    if (context.sessionStage == null) {
      Integer status = readInt(activeSession, "status");
      if (status != null) {
        context.sessionStage = status == 1 ? "ACTIVE" : "IDLE";
      }
    }

    JSONObject driver = readObject(activeSession, "driver");
    if (driver != null) {
      context.driverId = readLong(driver, "id");
      context.driverCode = readString(driver, "driverCode");
      context.driverName = firstNonBlank(readString(driver, "driverName"), readString(driver, "name"));
      return;
    }
    context.driverId = readLong(activeSession, "driverId");
    context.driverCode = readString(activeSession, "driverCode");
    context.driverName = readString(activeSession, "driverName");
  }

  private void mergeSessionPayload(@NonNull EdgeLocalContext context, @NonNull JSONObject data) {
    JSONObject activeSession = readObject(data, "activeSession");
    if (activeSession != null) {
      mergeActiveSessionPayload(context, activeSession);
      return;
    }

    context.driverId = readLong(data, "driverId");
    context.driverCode = readString(data, "driverCode");
    context.driverName = readString(data, "driverName");
    context.sessionId = firstNonNull(readLong(data, "sessionId"), readLong(data, "id"));
    context.sessionNo = readString(data, "sessionNo");
    context.signedInAt = firstNonBlank(readString(data, "signInTime"), readString(data, "signedInAt"));
    context.sessionClosedReason = readString(data, "closedReason");
    context.sessionStage = normalizeUpper(readString(data, "sessionStage"));
    if (context.sessionStage == null) {
      Integer status = readInt(data, "status");
      if (status != null) {
        context.sessionStage = status == 1 ? "ACTIVE" : "IDLE";
      }
    }
  }

  private void clearContextSnapshot(@NonNull EdgeLocalContext context) {
    context.deviceId = null;
    context.deviceName = null;
    context.enterpriseId = null;
    context.enterpriseName = null;
    context.fleetId = null;
    context.fleetName = null;
    context.vehicleId = null;
    context.vehiclePlateNumber = null;
    context.effectiveStage = null;
    context.configVersion = null;
    clearSessionSnapshot(context);
  }

  private void clearSessionSnapshot(@NonNull EdgeLocalContext context) {
    context.driverId = null;
    context.driverCode = null;
    context.driverName = null;
    context.sessionId = null;
    context.sessionNo = null;
    context.sessionStage = null;
    context.signedInAt = null;
    context.sessionClosedReason = null;
  }

  private boolean isSessionActive(@NonNull EdgeLocalContext context) {
    return "ACTIVE".equals(context.sessionStage)
      || (context.sessionId != null && context.sessionStage == null);
  }

  @NonNull
  private String resolveNonSessionStage(@NonNull EdgeLocalContext context) {
    if (context.isDisabled()) {
      return "DISABLED";
    }
    if (context.hasVehicleBinding()) {
      return "READY_SIGN_IN";
    }
    if (context.hasEnterpriseBinding()) {
      return "WAITING_VEHICLE";
    }
    return "CLAIM_ENTERPRISE";
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

  @NonNull
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

  @Nullable
  private JSONObject readObject(@NonNull JSONObject json, @NonNull String key) {
    return json.optJSONObject(key);
  }

  @Nullable
  private Long readNestedLong(@NonNull JSONObject json, @NonNull String key, @NonNull String nestedKey) {
    JSONObject nested = readObject(json, key);
    return nested == null ? null : readLong(nested, nestedKey);
  }

  @Nullable
  private String readNestedString(@NonNull JSONObject json, @NonNull String key, @NonNull String nestedKey) {
    JSONObject nested = readObject(json, key);
    return nested == null ? null : readString(nested, nestedKey);
  }

  @Nullable
  private Long readLong(@NonNull JSONObject json, @NonNull String key) {
    if (!json.has(key) || json.isNull(key)) {
      return null;
    }
    Object value = json.opt(key);
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      String text = ((String) value).trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  @Nullable
  private Integer readInt(@NonNull JSONObject json, @NonNull String key) {
    if (!json.has(key) || json.isNull(key)) {
      return null;
    }
    Object value = json.opt(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      String text = ((String) value).trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  @Nullable
  private String readString(@NonNull JSONObject json, @NonNull String key) {
    if (!json.has(key) || json.isNull(key)) {
      return null;
    }
    String value = json.optString(key, null);
    return value == null || value.trim().isEmpty() ? null : value;
  }

  @Nullable
  private String normalizeUpper(@Nullable String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim().toUpperCase();
  }

  @Nullable
  private <T> T firstNonNull(@Nullable T first, @Nullable T second) {
    return first != null ? first : second;
  }

  @Nullable
  private String firstNonBlank(@Nullable String first, @Nullable String second) {
    return first != null && !first.trim().isEmpty() ? first : second;
  }
}
