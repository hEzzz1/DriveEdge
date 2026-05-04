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
  @Nullable public Long driverId;
  @Nullable public String driverCode;
  @Nullable public String driverName;
  @Nullable public Long sessionId;
  @Nullable public String sessionNo;
  @Nullable public String sessionStage;
  @Nullable public String configVersion;
  @Nullable public String runtimeConfigVersion;
  @Nullable public String runtimeConfigJson;
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
    copy.driverId = driverId;
    copy.driverCode = driverCode;
    copy.driverName = driverName;
    copy.sessionId = sessionId;
    copy.sessionNo = sessionNo;
    copy.sessionStage = sessionStage;
    copy.configVersion = configVersion;
    copy.runtimeConfigVersion = runtimeConfigVersion;
    copy.runtimeConfigJson = runtimeConfigJson;
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
    configVersion = null;
    runtimeConfigVersion = null;
    runtimeConfigJson = null;
    lastSyncAt = null;
    clearSession();
  }
}
