package com.driveedge.app.edge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EdgeDeviceCodeProviderTest {
  @Test
  public void buildFromAndroidIdUsesStableUppercasePrefix() {
    assertEquals("EDGE-ABC123XYZ", EdgeDeviceCodeProvider.buildFromAndroidId("abc-123_xyz"));
  }

  @Test
  public void buildFromAndroidIdReturnsNullWhenSourceBlank() {
    assertNull(EdgeDeviceCodeProvider.buildFromAndroidId(" - _ "));
  }

  @Test
  public void normalizeStoredDeviceCodeTrimsExistingValue() {
    assertEquals("EDGE-001", EdgeDeviceCodeProvider.normalizeStoredDeviceCode("  EDGE-001  "));
  }

  @Test
  public void randomFallbackBuildsPrefixedCode() {
    String deviceCode = EdgeDeviceCodeProvider.buildFromRandomUuid();

    assertNotNull(deviceCode);
    assertTrue(deviceCode.startsWith("EDGE-"));
    assertTrue(deviceCode.length() > "EDGE-".length());
  }
}
