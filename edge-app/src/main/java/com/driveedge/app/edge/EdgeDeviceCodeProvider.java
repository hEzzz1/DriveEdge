package com.driveedge.app.edge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

public final class EdgeDeviceCodeProvider {
  private static final String DEVICE_CODE_PREFIX = "EDGE-";

  @NonNull
  public String resolve(@NonNull Context context, @Nullable String storedDeviceCode) {
    String currentDeviceCode = normalizeStoredDeviceCode(storedDeviceCode);
    if (currentDeviceCode != null) {
      return currentDeviceCode;
    }
    String androidIdDeviceCode = buildFromAndroidId(readAndroidId(context));
    if (androidIdDeviceCode != null) {
      return androidIdDeviceCode;
    }
    return buildFromRandomUuid();
  }

  @Nullable
  static String normalizeStoredDeviceCode(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Nullable
  static String buildFromAndroidId(@Nullable String androidId) {
    String normalized = normalizeToken(androidId);
    return normalized == null ? null : DEVICE_CODE_PREFIX + normalized;
  }

  @NonNull
  static String buildFromRandomUuid() {
    return DEVICE_CODE_PREFIX + normalizeToken(UUID.randomUUID().toString());
  }

  @SuppressLint("HardwareIds")
  @Nullable
  private String readAndroidId(@NonNull Context context) {
    return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
  }

  @Nullable
  private static String normalizeToken(@Nullable String rawValue) {
    if (rawValue == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder(rawValue.length());
    for (int index = 0; index < rawValue.length(); index++) {
      char current = rawValue.charAt(index);
      if (Character.isLetterOrDigit(current)) {
        builder.append(Character.toUpperCase(current));
      }
    }
    return builder.length() == 0 ? null : builder.toString();
  }
}
