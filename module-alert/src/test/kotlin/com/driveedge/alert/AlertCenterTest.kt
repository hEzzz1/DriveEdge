package com.driveedge.alert

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.UploadStatus
import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertCenterTest {
  @Test
  fun `process executes configured channels when not throttled`() {
    val invokedChannels = mutableListOf<AlertChannel>()
    val center =
      AlertCenter(
        actions =
          AlertActionSet(
            sound = AlertAction { _, _ -> invokedChannels += AlertChannel.SOUND; true },
            vibration = AlertAction { _, _ -> invokedChannels += AlertChannel.VIBRATION; true },
            uiPrompt = AlertAction { _, _ -> invokedChannels += AlertChannel.UI_PROMPT; true },
          ),
        clock = Clock.fixed(Instant.parse("2026-04-09T10:00:00Z"), ZoneOffset.UTC),
      )

    val record = center.process(event(eventId = "evt-1", riskLevel = RiskLevel.HIGH, windowEndMs = 10_000L))

    assertEquals(AlertExecutionStatus.EXECUTED, record.status)
    assertEquals(
      setOf(AlertChannel.SOUND, AlertChannel.VIBRATION, AlertChannel.UI_PROMPT),
      record.attemptedChannels,
    )
    assertEquals(record.attemptedChannels, record.succeededChannels)
    assertTrue(record.failedChannels.isEmpty())
    assertEquals(
      listOf(AlertChannel.SOUND, AlertChannel.VIBRATION, AlertChannel.UI_PROMPT),
      invokedChannels,
    )
  }

  @Test
  fun `process throttles duplicated alert in throttle window`() {
    val invokeCount = mutableMapOf<AlertChannel, Int>()
    val center =
      AlertCenter(
        actions =
          AlertActionSet(
            sound = AlertAction { _, _ -> invokeCount[AlertChannel.SOUND] = (invokeCount[AlertChannel.SOUND] ?: 0) + 1; true },
            vibration = AlertAction { _, _ -> invokeCount[AlertChannel.VIBRATION] = (invokeCount[AlertChannel.VIBRATION] ?: 0) + 1; true },
            uiPrompt = AlertAction { _, _ -> invokeCount[AlertChannel.UI_PROMPT] = (invokeCount[AlertChannel.UI_PROMPT] ?: 0) + 1; true },
          ),
      )

    val first = center.process(event(eventId = "evt-1", riskLevel = RiskLevel.HIGH, windowEndMs = 10_000L))
    val second = center.process(event(eventId = "evt-2", riskLevel = RiskLevel.HIGH, windowEndMs = 11_000L))

    assertEquals(AlertExecutionStatus.EXECUTED, first.status)
    assertEquals(AlertExecutionStatus.THROTTLED, second.status)
    assertEquals(1, invokeCount[AlertChannel.SOUND])
    assertEquals(1, invokeCount[AlertChannel.VIBRATION])
    assertEquals(1, invokeCount[AlertChannel.UI_PROMPT])
  }

  @Test
  fun `process emits again when event passes throttle window`() {
    val soundInvokeCount = mutableListOf<String>()
    val center =
      AlertCenter(
        actions =
          AlertActionSet(
            sound = AlertAction { event, _ -> soundInvokeCount += event.eventId; true },
          ),
      )

    center.process(event(eventId = "evt-1", riskLevel = RiskLevel.HIGH, windowEndMs = 10_000L))
    center.process(event(eventId = "evt-2", riskLevel = RiskLevel.HIGH, windowEndMs = 13_001L))

    assertEquals(listOf("evt-1", "evt-2"), soundInvokeCount)
  }

  @Test
  fun `process skips when risk level policy has no channel`() {
    val center = AlertCenter()

    val record = center.process(event(eventId = "evt-1", riskLevel = RiskLevel.NONE, windowEndMs = 10_000L))

    assertEquals(AlertExecutionStatus.SKIPPED_NO_CHANNEL, record.status)
    assertTrue(record.attemptedChannels.isEmpty())
    assertTrue(record.succeededChannels.isEmpty())
    assertTrue(record.failedChannels.isEmpty())
  }

  @Test
  fun `process records failed channel execution`() {
    val center =
      AlertCenter(
        actions =
          AlertActionSet(
            sound = AlertAction { _, _ -> true },
            vibration = AlertAction { _, _ -> false },
            uiPrompt = AlertAction { _, _ -> true },
          ),
      )

    val record = center.process(event(eventId = "evt-1", riskLevel = RiskLevel.HIGH, windowEndMs = 10_000L))

    assertEquals(AlertExecutionStatus.EXECUTED, record.status)
    assertEquals(setOf(AlertChannel.VIBRATION), record.failedChannels)
    assertTrue(record.succeededChannels.contains(AlertChannel.SOUND))
    assertTrue(record.succeededChannels.contains(AlertChannel.UI_PROMPT))
  }

  private fun event(
    eventId: String,
    riskLevel: RiskLevel,
    windowEndMs: Long,
  ): EdgeEvent =
    EdgeEvent(
      eventId = eventId,
      fleetId = "FLEET-1",
      vehicleId = "VEH-1",
      driverId = "DRIVER-1",
      eventTimeUtc = Instant.ofEpochMilli(windowEndMs).toString(),
      fatigueScore = 0.9,
      distractionScore = 0.2,
      riskLevel = riskLevel,
      dominantRiskType = RiskType.FATIGUE,
      triggerReasons = setOf(TriggerReason.FATIGUE_PERCLOS_SUSTAINED),
      algorithmVer = "algo-v1",
      uploadStatus = UploadStatus.PENDING,
      windowStartMs = (windowEndMs - 3_000L).coerceAtLeast(0L),
      windowEndMs = windowEndMs,
      createdAtMs = 1_000L,
    )
}
