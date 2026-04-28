package com.driveedge.app.edge;

import androidx.annotation.Nullable;

public final class EdgeApiException extends Exception {
  public final int code;
  @Nullable public final String traceId;

  public EdgeApiException(int code, String message, @Nullable String traceId) {
    super(message);
    this.code = code;
    this.traceId = traceId;
  }
}
