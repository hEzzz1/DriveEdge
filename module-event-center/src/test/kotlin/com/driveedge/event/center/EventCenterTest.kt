package com.driveedge.event.center

import com.driveedge.risk.engine.RiskEventCandidate
import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EventCenterTest {
  @Test
  fun `process emits and persists event when trigger is true`() {
    val eventStore = InMemoryEventStore()
    val eventCenter =
      EventCenter(
        config =
          EventCenterConfig(
            deviceCode = "DEV-001",
            vehicleId = "VEH-1001",
            enterpriseId = "100",
            fleetId = "FLEET-1",
            driverId = "DRIVER-9",
            configVersion = "ruleset/1/1/1",
            algorithmVersion = "yolo-v8n-int8-20260407",
            debounceWindowMs = 5_000L,
          ),
        eventStore = eventStore,
        clock = Clock.fixed(Instant.parse("2026-04-09T10:11:12Z"), ZoneOffset.UTC),
      )

    val event = eventCenter.process(candidate(windowEndMs = 1_000L))

    assertNotNull(event)
    assertEquals(1, eventStore.events.size)
    assertEquals(UploadStatus.PENDING, event.uploadStatus)
    assertEquals("DEV-001", event.deviceCode)
    assertEquals("VEH-1001", event.vehicleId)
    assertEquals("FLEET-1", event.fleetId)
    assertEquals("DRIVER-9", event.driverId)
  }

  @Test
  fun `process returns null when candidate does not trigger`() {
    val eventStore = InMemoryEventStore()
    val eventCenter =
      EventCenter(
        config = EventCenterConfig(deviceCode = "DEV-001", vehicleId = "VEH-1001"),
        eventStore = eventStore,
      )

    val noTriggerCandidate = candidate(shouldTrigger = false, windowEndMs = 1_000L)
    val event = eventCenter.process(noTriggerCandidate)

    assertNull(event)
    assertEquals(0, eventStore.events.size)
  }

  @Test
  fun `process debounces duplicated event in window`() {
    val eventStore = InMemoryEventStore()
    val eventCenter =
      EventCenter(
        config = EventCenterConfig(deviceCode = "DEV-001", vehicleId = "VEH-1001", debounceWindowMs = 5_000L),
        eventStore = eventStore,
      )

    val first = eventCenter.process(candidate(windowEndMs = 10_000L))
    val second = eventCenter.process(candidate(windowEndMs = 12_000L))
    val third = eventCenter.process(candidate(windowEndMs = 16_001L))

    assertNotNull(first)
    assertNull(second)
    assertNotNull(third)
    assertEquals(2, eventStore.events.size)
  }

  private fun candidate(
    shouldTrigger: Boolean = true,
    windowEndMs: Long,
  ): RiskEventCandidate =
    RiskEventCandidate(
      windowStartMs = (windowEndMs - 3_000L).coerceAtLeast(0L),
      windowEndMs = windowEndMs,
      fatigueScore = 0.8,
      distractionScore = 0.25,
      riskLevel = RiskLevel.HIGH,
      dominantRiskType = RiskType.FATIGUE,
      fatigueTriggered = shouldTrigger,
      distractionTriggered = false,
      shouldTrigger = shouldTrigger,
      triggerReasons = setOf(TriggerReason.FATIGUE_PERCLOS_SUSTAINED),
    )

  private class InMemoryEventStore : EdgeEventStore {
    val events = mutableListOf<EdgeEvent>()

    override fun append(event: EdgeEvent) {
      events += event
    }
  }
}
