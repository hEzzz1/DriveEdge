package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class EdgeActivationCodeParser {
  private static final String[] QUERY_PARAM_KEYS = {
    "enterpriseActivationCode",
    "activationCode",
    "bindCode",
    "code"
  };

  @Nullable
  public String parse(@Nullable String rawValue) {
    if (rawValue == null) {
      return null;
    }

    String normalized = rawValue.trim();
    if (normalized.isEmpty()) {
      return null;
    }

    String queryValue = parseQueryValue(normalized);
    if (queryValue != null) {
      return queryValue;
    }

    return normalized;
  }

  @Nullable
  private String parseQueryValue(@NonNull String rawValue) {
    try {
      URI uri = URI.create(rawValue);
      if (uri.getScheme() == null || uri.getScheme().trim().isEmpty()) {
        return null;
      }
      String query = uri.getRawQuery();
      if (query == null || query.trim().isEmpty()) {
        return null;
      }
      for (String key : QUERY_PARAM_KEYS) {
        String value = findQueryParameter(query, key);
        if (value != null && !value.trim().isEmpty()) {
          return value.trim();
        }
      }
      return null;
    } catch (Exception ignored) {
      return null;
    }
  }

  @Nullable
  private String findQueryParameter(@NonNull String rawQuery, @NonNull String targetKey) {
    String[] pairs = rawQuery.split("&");
    for (String pair : pairs) {
      if (pair.isEmpty()) {
        continue;
      }
      int separatorIndex = pair.indexOf('=');
      String key = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
      if (!targetKey.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
        continue;
      }
      String value = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
    return null;
  }
}
