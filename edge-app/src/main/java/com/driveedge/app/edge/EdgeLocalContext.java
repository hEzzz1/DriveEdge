package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class EdgeLocalContext {
  @Nullable public Long deviceId;
  @Nullable public String deviceCode;
  @Nullable public String deviceName;
  @Nullable public String activationCode;
  @Nullable public String deviceToken;
  @Nullable public Long enterpriseId;
  @Nullable public String enterpriseName;
  @Nullable public Long fleetId;
  @Nullable public String fleetName;
  @Nullable public Long vehicleId;
  @Nullable public String vehiclePlateNumber;
  @Nullable public String effectiveStage;
  @Nullable public Long bindRequestId;
  @Nullable public Long bindRequestEnterpriseId;
  @Nullable public String bindRequestEnterpriseName;
  @Nullable public String bindRequestCodeMasked;
  @Nullable public String bindRequestSource;
  @Nullable public String bindRequestStatus;
  @Nullable public String bindRequestSubmittedAt;
  @Nullable public String bindRequestReviewedAt;
  @Nullable public String bindRequestApproveRemark;
  @Nullable public String bindRequestRejectReason;
  @Nullable public String bindRequestExpiresAt;
  @Nullable public Long driverId;
  @Nullable public String driverCode;
  @Nullable public String driverName;
  @Nullable public Long sessionId;
  @Nullable public String sessionNo;
  @Nullable public String sessionStage;
  @Nullable public String configVersion;
  @Nullable public String signedInAt;
  @Nullable public String lastSyncAt;
  @Nullable public String sessionClosedReason;

  @NonNull
  public static EdgeLocalContext empty() {
    return new EdgeLocalContext();
  }

  @NonNull
  public EdgeLocalContext copy() {
    EdgeLocalContext copy = new EdgeLocalContext();
    copy.deviceId = deviceId;
    copy.deviceCode = deviceCode;
    copy.deviceName = deviceName;
    copy.activationCode = activationCode;
    copy.deviceToken = deviceToken;
    copy.enterpriseId = enterpriseId;
    copy.enterpriseName = enterpriseName;
    copy.fleetId = fleetId;
    copy.fleetName = fleetName;
    copy.vehicleId = vehicleId;
    copy.vehiclePlateNumber = vehiclePlateNumber;
    copy.effectiveStage = effectiveStage;
    copy.bindRequestId = bindRequestId;
    copy.bindRequestEnterpriseId = bindRequestEnterpriseId;
    copy.bindRequestEnterpriseName = bindRequestEnterpriseName;
    copy.bindRequestCodeMasked = bindRequestCodeMasked;
    copy.bindRequestSource = bindRequestSource;
    copy.bindRequestStatus = bindRequestStatus;
    copy.bindRequestSubmittedAt = bindRequestSubmittedAt;
    copy.bindRequestReviewedAt = bindRequestReviewedAt;
    copy.bindRequestApproveRemark = bindRequestApproveRemark;
    copy.bindRequestRejectReason = bindRequestRejectReason;
    copy.bindRequestExpiresAt = bindRequestExpiresAt;
    copy.driverId = driverId;
    copy.driverCode = driverCode;
    copy.driverName = driverName;
    copy.sessionId = sessionId;
    copy.sessionNo = sessionNo;
    copy.sessionStage = sessionStage;
    copy.configVersion = configVersion;
    copy.signedInAt = signedInAt;
    copy.lastSyncAt = lastSyncAt;
    copy.sessionClosedReason = sessionClosedReason;
    return copy;
  }

  public boolean hasDeviceIdentity() {
    return deviceCode != null && !deviceCode.trim().isEmpty()
      && deviceToken != null && !deviceToken.trim().isEmpty();
  }

  public boolean hasEnterpriseBinding() {
    return enterpriseId != null;
  }

  public boolean hasVehicleBinding() {
    return hasEnterpriseBinding() && vehicleId != null;
  }

  public boolean hasActiveSession() {
    return "IN_SESSION".equals(effectiveStage)
      || "ACTIVE".equals(sessionStage)
      || (sessionId != null && sessionStage == null);
  }

  public boolean hasPendingBindRequest() {
    return "PENDING_APPROVAL".equals(effectiveStage) || "PENDING".equals(bindRequestStatus);
  }

  public boolean hasRejectedBindRequest() {
    return "REJECTED".equals(bindRequestStatus);
  }

  public boolean hasExpiredBindRequest() {
    return "EXPIRED".equals(bindRequestStatus);
  }

  public boolean isDisabled() {
    return "DISABLED".equals(effectiveStage);
  }

  public boolean isSignInAllowed() {
    return "READY_SIGN_IN".equals(effectiveStage) || (effectiveStage == null && hasVehicleBinding());
  }

  public void clearSession() {
    driverId = null;
    driverCode = null;
    driverName = null;
    sessionId = null;
    sessionNo = null;
    sessionStage = null;
    signedInAt = null;
    sessionClosedReason = null;
  }

  public void clearDeviceRuntime() {
    deviceId = null;
    deviceName = null;
    activationCode = null;
    deviceToken = null;
    enterpriseId = null;
    enterpriseName = null;
    fleetId = null;
    fleetName = null;
    vehicleId = null;
    vehiclePlateNumber = null;
    effectiveStage = null;
    bindRequestId = null;
    bindRequestEnterpriseId = null;
    bindRequestEnterpriseName = null;
    bindRequestCodeMasked = null;
    bindRequestSource = null;
    bindRequestStatus = null;
    bindRequestSubmittedAt = null;
    bindRequestReviewedAt = null;
    bindRequestApproveRemark = null;
    bindRequestRejectReason = null;
    bindRequestExpiresAt = null;
    configVersion = null;
    lastSyncAt = null;
    clearSession();
  }

  @NonNull
  public String resolvedBindEnterpriseName() {
    if (bindRequestEnterpriseName != null && !bindRequestEnterpriseName.trim().isEmpty()) {
      return bindRequestEnterpriseName;
    }
    if (enterpriseName != null && !enterpriseName.trim().isEmpty()) {
      return enterpriseName;
    }
    if (bindRequestEnterpriseId != null) {
      return String.valueOf(bindRequestEnterpriseId);
    }
    if (enterpriseId != null) {
      return String.valueOf(enterpriseId);
    }
    return "-";
  }
}
