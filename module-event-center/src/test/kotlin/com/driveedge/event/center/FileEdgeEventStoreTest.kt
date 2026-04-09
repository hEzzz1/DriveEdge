package com.driveedge.event.center

import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileEdgeEventStoreTest {
  @Test
  fun `append writes one escaped line to file`() {
    val tempFile = Files.createTempFile("driveedge-event-center-", ".log")
    try {
      val store = FileEdgeEventStore(tempFile)
      store.append(
        EdgeEvent(
          eventId = "evt_VEH-1001_20260409081011_0001",
          fleetId = "FLEET-1",
          vehicleId = "VEH-1001",
          driverId = "DRIVER-9",
          eventTimeUtc = "2026-04-09T08:10:11Z",
          fatigueScore = 0.9,
          distractionScore = 0.1,
          riskLevel = RiskLevel.HIGH,
          dominantRiskType = RiskType.FATIGUE,
          triggerReasons = setOf(TriggerReason.FATIGUE_PERCLOS_SUSTAINED),
          algorithmVer = "yolo-v8n-int8-20260407",
          uploadStatus = UploadStatus.PENDING,
          windowStartMs = 1_000L,
          windowEndMs = 4_000L,
          createdAtMs = 5_000L,
        ),
      )

      val lines = Files.readAllLines(tempFile)
      assertEquals(1, lines.size)
      assertTrue(lines[0].contains("evt_VEH-1001_20260409081011_0001"))
      assertTrue(lines[0].contains("PENDING"))
      assertTrue(lines[0].contains("FATIGUE_PERCLOS_SUSTAINED"))
    } finally {
      tempFile.deleteIfExists()
    }
  }
}
