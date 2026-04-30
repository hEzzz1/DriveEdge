package com.driveedge.app.edge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public final class EdgeApiClientTest {
  @Test
  public void mergeContextPayloadParsesClaimResponse() throws Exception {
    EdgeApiClient client = new EdgeApiClient();
    EdgeLocalContext context = EdgeLocalContext.empty();

    JSONObject payload = new JSONObject(
      "{"
        + "\"device\":{"
        + "\"id\":101,"
        + "\"deviceCode\":\"EDGE-0001\","
        + "\"deviceName\":\"前挡摄像头A\","
        + "\"deviceToken\":\"TOKEN_VALUE\","
        + "\"lifecycleStatus\":\"BOUND\""
        + "},"
        + "\"enterprise\":{\"id\":12,\"name\":\"测试企业\"},"
        + "\"fleet\":null,"
        + "\"vehicle\":null,"
        + "\"vehicleBindStatus\":\"UNASSIGNED\","
        + "\"sessionStage\":\"IDLE\","
        + "\"effectiveStage\":\"WAITING_VEHICLE\","
        + "\"claimedAt\":\"2026-04-30T03:00:00Z\","
        + "\"activeSession\":null"
        + "}"
    );

    client.mergeContextPayload(context, payload);

    assertEquals(Long.valueOf(101L), context.deviceId);
    assertEquals("EDGE-0001", context.deviceCode);
    assertEquals("前挡摄像头A", context.deviceName);
    assertEquals("TOKEN_VALUE", context.deviceToken);
    assertEquals(Long.valueOf(12L), context.enterpriseId);
    assertEquals("测试企业", context.enterpriseName);
    assertEquals("WAITING_VEHICLE", context.effectiveStage);
    assertNull(context.vehicleId);
    assertNull(context.sessionId);
  }

  @Test
  public void mergeContextPayloadParsesActiveSessionObject() throws Exception {
    EdgeApiClient client = new EdgeApiClient();
    EdgeLocalContext context = EdgeLocalContext.empty();

    JSONObject payload = new JSONObject(
      "{"
        + "\"device\":{\"id\":101,\"deviceCode\":\"EDGE-0001\",\"deviceName\":\"前挡摄像头A\"},"
        + "\"effectiveStage\":\"IN_SESSION\","
        + "\"sessionStage\":\"ACTIVE\","
        + "\"enterprise\":{\"id\":12,\"name\":\"测试企业\"},"
        + "\"fleet\":{\"id\":3,\"name\":\"一车队\"},"
        + "\"vehicle\":{\"id\":5,\"plateNumber\":\"沪A12345\"},"
        + "\"currentBindRequest\":null,"
        + "\"activeSession\":{"
        + "\"id\":501,"
        + "\"sessionNo\":\"S-0001\","
        + "\"signInTime\":\"2026-04-30T03:20:00Z\","
        + "\"driver\":{\"id\":9,\"driverCode\":\"DRV-001\",\"name\":\"张三\"}"
        + "}"
        + "}"
    );

    client.mergeContextPayload(context, payload);

    assertEquals("IN_SESSION", context.effectiveStage);
    assertEquals("ACTIVE", context.sessionStage);
    assertEquals(Long.valueOf(501L), context.sessionId);
    assertEquals("S-0001", context.sessionNo);
    assertEquals("2026-04-30T03:20:00Z", context.signedInAt);
    assertEquals(Long.valueOf(9L), context.driverId);
    assertEquals("DRV-001", context.driverCode);
    assertEquals("张三", context.driverName);
    assertEquals(Long.valueOf(5L), context.vehicleId);
    assertEquals("沪A12345", context.vehiclePlateNumber);
  }
}
