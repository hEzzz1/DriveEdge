package com.driveedge.alert

import com.driveedge.event.center.EdgeEvent
import java.time.Clock
import kotlin.math.max

class AlertCenter(
  private val config: AlertCenterConfig = AlertCenterConfig(),
  private val actions: AlertActionSet = AlertActionSet(),
  private val throttler: AlertThrottler = AlertThrottler(),
  private val messageFormatter: (EdgeEvent) -> String = ::defaultMessage,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun process(event: EdgeEvent): AlertExecutionRecord {
    val policy = config.policyOf(event.riskLevel)
    val eventTimeMs = max(event.windowEndMs, 0L)
    val throttleKey = buildThrottleKey(event)
    val message = messageFormatter(event)

    if (policy.channels.isEmpty()) {
      return AlertExecutionRecord(
        eventId = event.eventId,
        eventTimeMs = eventTimeMs,
        vehicleId = event.vehicleId,
        riskLevel = event.riskLevel,
        dominantRiskType = event.dominantRiskType,
        throttleKey = throttleKey,
        throttleWindowMs = policy.throttleWindowMs,
        status = AlertExecutionStatus.SKIPPED_NO_CHANNEL,
        attemptedChannels = emptySet(),
        succeededChannels = emptySet(),
        failedChannels = emptySet(),
        message = message,
        executedAtMs = clock.millis(),
      )
    }

    if (!throttler.shouldAlert(throttleKey, eventTimeMs, policy.throttleWindowMs)) {
      return AlertExecutionRecord(
        eventId = event.eventId,
        eventTimeMs = eventTimeMs,
        vehicleId = event.vehicleId,
        riskLevel = event.riskLevel,
        dominantRiskType = event.dominantRiskType,
        throttleKey = throttleKey,
        throttleWindowMs = policy.throttleWindowMs,
        status = AlertExecutionStatus.THROTTLED,
        attemptedChannels = emptySet(),
        succeededChannels = emptySet(),
        failedChannels = emptySet(),
        message = message,
        executedAtMs = clock.millis(),
      )
    }

    val attemptedChannels = policy.channels.toList().sortedBy { it.ordinal }.toSet()
    val succeededChannels = linkedSetOf<AlertChannel>()
    val failedChannels = linkedSetOf<AlertChannel>()

    for (channel in attemptedChannels) {
      val success = actions.actionOf(channel).execute(event, message)
      if (success) {
        succeededChannels += channel
      } else {
        failedChannels += channel
      }
    }

    return AlertExecutionRecord(
      eventId = event.eventId,
      eventTimeMs = eventTimeMs,
      vehicleId = event.vehicleId,
      riskLevel = event.riskLevel,
      dominantRiskType = event.dominantRiskType,
      throttleKey = throttleKey,
      throttleWindowMs = policy.throttleWindowMs,
      status = AlertExecutionStatus.EXECUTED,
      attemptedChannels = attemptedChannels,
      succeededChannels = succeededChannels,
      failedChannels = failedChannels,
      message = message,
      executedAtMs = clock.millis(),
    )
  }

  private fun buildThrottleKey(event: EdgeEvent): String {
    val sortedReasons = event.triggerReasons.map { it.name }.sorted().joinToString("+")
    return listOf(
      event.vehicleId,
      event.riskLevel.name,
      event.dominantRiskType?.name ?: "UNKNOWN",
      sortedReasons,
    ).joinToString("|")
  }

  private companion object {
    fun defaultMessage(event: EdgeEvent): String {
      val riskType = event.dominantRiskType?.name ?: "UNKNOWN"
      val fatigue = "%.2f".format(event.fatigueScore.coerceIn(0.0, 1.0))
      val distraction = "%.2f".format(event.distractionScore.coerceIn(0.0, 1.0))
      return "${event.riskLevel.name} risk alert ($riskType), fatigue=$fatigue, distraction=$distraction"
    }
  }
}
