package com.driveedge.app.edge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class EdgeLocalContext {
  @Nullable public Long deviceId;
  @Nullable public String deviceCode;
  @Nullable public String deviceName;
  @Nullable public String deviceToken;
  @Nullable public Long enterpriseId;
  @Nullable public String enterpriseName;
  @Nullable public Long fleetId;
  @Nullable public String fleetName;
  @Nullable public Long vehicleId;
  @Nullable public String vehiclePlateNumber;
  @Nullable public Long driverId;
  @Nullable public String driverCode;
  @Nullable public String driverName;
  @Nullable public Long sessionId;
  @Nullable public String sessionNo;
  @Nullable public Byte sessionStatus;
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
    copy.deviceToken = deviceToken;
    copy.enterpriseId = enterpriseId;
    copy.enterpriseName = enterpriseName;
    copy.fleetId = fleetId;
    copy.fleetName = fleetName;
    copy.vehicleId = vehicleId;
    copy.vehiclePlateNumber = vehiclePlateNumber;
    copy.driverId = driverId;
    copy.driverCode = driverCode;
    copy.driverName = driverName;
    copy.sessionId = sessionId;
    copy.sessionNo = sessionNo;
    copy.sessionStatus = sessionStatus;
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

  public boolean hasVehicleBinding() {
    return vehicleId != null && deviceCode != null && !deviceCode.trim().isEmpty();
  }

  public boolean hasActiveSession() {
    return sessionId != null && sessionStatus != null && sessionStatus == (byte) 1;
  }

  public void clearSession() {
    driverId = null;
    driverCode = null;
    driverName = null;
    sessionId = null;
    sessionNo = null;
    sessionStatus = null;
    signedInAt = null;
    sessionClosedReason = null;
  }
}
