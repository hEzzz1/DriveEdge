package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class EdgeFlowController {
  private static final int EDGE_UNAUTHORIZED_CODE = 40101;
  private static final int EDGE_NOT_FOUND_CODE = 40401;
  private static final int EDGE_NOT_BOUND_ENTERPRISE_CODE = 40901;
  private static final int EDGE_NOT_BOUND_VEHICLE_CODE = 40905;
  private static final int EDGE_VEHICLE_OCCUPIED_CODE = 40906;
  private static final int EDGE_ACTIVE_SESSION_CONFLICT_CODE = 40907;
  private static final int EDGE_ENTERPRISE_ACTIVATION_CODE_NOT_FOUND_CODE = 40908;
  private static final int EDGE_ENTERPRISE_ACTIVATION_CODE_EXPIRED_CODE = 40909;
  private static final int EDGE_ENTERPRISE_ACTIVATION_CODE_DISABLED_CODE = 40910;
  private static final int EDGE_BOUND_OTHER_ENTERPRISE_CODE = 40911;

  @NonNull
  private final EdgeApiClient edgeApiClient;

  public enum Destination {
    CLAIM_ENTERPRISE,
    WAITING_VEHICLE,
    SIGN_IN,
    CAPTURE,
    DISABLED
  }

  public EdgeFlowController() {
    this(new EdgeApiClient());
  }

  public EdgeFlowController(@NonNull EdgeApiClient edgeApiClient) {
    this.edgeApiClient = edgeApiClient;
  }

  @NonNull
  public EdgeLocalContext syncContext(@NonNull EdgeLocalContext cachedContext) throws Exception {
    if (cachedContext.deviceCode == null || cachedContext.deviceCode.trim().isEmpty()) {
      return toClaimEnterpriseContext(cachedContext.copy());
    }
    if (!cachedContext.hasDeviceIdentity()) {
      return toClaimEnterpriseContext(cachedContext.copy());
    }
    try {
      return syncRuntimeConfig(edgeApiClient.fetchContext(cachedContext));
    } catch (EdgeApiException error) {
      if (error.code == EDGE_UNAUTHORIZED_CODE || error.code == EDGE_NOT_FOUND_CODE) {
        return toClaimEnterpriseContext(cachedContext.copy());
      }
      throw error;
    }
  }

  @NonNull
  private EdgeLocalContext syncRuntimeConfig(@NonNull EdgeLocalContext context) {
    try {
      return edgeApiClient.fetchEdgeConfig(context);
    } catch (Exception ignored) {
      return context;
    }
  }

  @NonNull
  public Destination resolveDestination(@NonNull EdgeLocalContext context) {
    String effectiveStage = normalized(context.effectiveStage);
    if (effectiveStage != null) {
      switch (effectiveStage) {
        case "CLAIM_ENTERPRISE":
          return Destination.CLAIM_ENTERPRISE;
        case "WAITING_VEHICLE":
          return Destination.WAITING_VEHICLE;
        case "READY_SIGN_IN":
          return Destination.SIGN_IN;
        case "IN_SESSION":
          return Destination.CAPTURE;
        case "DISABLED":
          return Destination.DISABLED;
        default:
          break;
      }
    }

    if (context.hasActiveSession()) {
      return Destination.CAPTURE;
    }
    if (context.isDisabled()) {
      return Destination.DISABLED;
    }
    if (context.isSignInAllowed()) {
      return Destination.SIGN_IN;
    }
    if (context.hasEnterpriseBinding()) {
      return Destination.WAITING_VEHICLE;
    }
    return Destination.CLAIM_ENTERPRISE;
  }

  @NonNull
  public String resolveStatusMessage(@NonNull EdgeLocalContext context) {
    switch (resolveDestination(context)) {
      case DISABLED:
        return "设备已禁用，请联系管理员";
      case CAPTURE:
        return "设备采集中";
      case SIGN_IN:
        return "设备已完成企业绑定，请驾驶员签到";
      case WAITING_VEHICLE:
        return "设备已绑定企业，待分配车辆";
      case CLAIM_ENTERPRISE:
      default:
        return "请输入企业激活码";
    }
  }

  @NonNull
  public String formatEdgeError(@NonNull EdgeLocalContext context, @NonNull Exception error) {
    if (error instanceof EdgeApiException) {
      EdgeApiException apiError = (EdgeApiException) error;
      switch (apiError.code) {
        case EDGE_NOT_FOUND_CODE:
          return "设备不存在，请重新绑定企业";
        case EDGE_NOT_BOUND_ENTERPRISE_CODE:
          return "设备未绑定企业，请输入企业激活码";
        case EDGE_NOT_BOUND_VEHICLE_CODE:
          return "设备未绑定车辆，请等待管理员分配车辆";
        case EDGE_VEHICLE_OCCUPIED_CODE:
          return "目标车辆已被其他设备占用";
        case EDGE_ACTIVE_SESSION_CONFLICT_CODE:
          return "设备存在活动会话，当前操作被禁止";
        case EDGE_ENTERPRISE_ACTIVATION_CODE_NOT_FOUND_CODE:
          return "企业激活码不存在，请重新核对";
        case EDGE_ENTERPRISE_ACTIVATION_CODE_EXPIRED_CODE:
          return "企业激活码已过期，请联系管理员获取新码";
        case EDGE_ENTERPRISE_ACTIVATION_CODE_DISABLED_CODE:
          return "企业激活码已停用，请联系管理员获取有效激活码";
        case EDGE_BOUND_OTHER_ENTERPRISE_CODE:
          return "设备已绑定其他企业，请联系管理员先解绑";
        default:
          String message = apiError.getMessage();
          return message == null ? String.valueOf(apiError.code) : message;
      }
    }
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  @Nullable
  public static String displayValue(@Nullable String textValue, @Nullable Long idValue) {
    if (textValue != null && !textValue.trim().isEmpty()) {
      return textValue;
    }
    return idValue == null ? "-" : String.valueOf(idValue);
  }

  @NonNull
  public static String displayText(@Nullable String value) {
    return value == null || value.trim().isEmpty() ? "-" : value;
  }

  @NonNull
  public static String displayDriverValue(@NonNull EdgeLocalContext context) {
    if (context.driverName != null && !context.driverName.trim().isEmpty()) {
      if (context.driverCode != null && !context.driverCode.trim().isEmpty()) {
        return context.driverName + " (" + context.driverCode + ")";
      }
      return context.driverName;
    }
    return "-";
  }

  @NonNull
  public String displayBindStatus(@NonNull EdgeLocalContext context) {
    switch (resolveDestination(context)) {
      case DISABLED:
        return "已禁用";
      case CAPTURE:
        return "采集中";
      case SIGN_IN:
        return "设备可签到";
      case WAITING_VEHICLE:
        return "设备已绑定企业，待分配车辆";
      case CLAIM_ENTERPRISE:
      default:
        return "未绑定企业";
    }
  }

  @NonNull
  private EdgeLocalContext toClaimEnterpriseContext(@NonNull EdgeLocalContext context) {
    context.clearDeviceRuntime();
    context.effectiveStage = "CLAIM_ENTERPRISE";
    return context;
  }

  @Nullable
  private String normalized(@Nullable String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim().toUpperCase();
  }
}
