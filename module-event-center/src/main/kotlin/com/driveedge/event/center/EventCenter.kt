package com.driveedge.event.center

import com.driveedge.risk.engine.RiskEventCandidate
import java.time.Clock
import java.time.Instant
import kotlin.math.max

class EventCenter
  @JvmOverloads
  constructor(
  private val config: EventCenterConfig,
  private val eventStore: EdgeEventStore,
  private val eventIdGenerator: EventIdGenerator = EventIdGenerator(config.vehicleId),
  private val eventDebouncer: EventDebouncer = EventDebouncer(config.debounceWindowMs),
  private val clock: Clock = Clock.systemUTC(),
) {
  fun process(candidate: RiskEventCandidate): EdgeEvent? {
    if (!candidate.shouldTrigger) {
      return null
    }

    val eventTimeMs = max(candidate.windowEndMs, 0L)
    val debounceKey = buildDebounceKey(candidate)
    if (!eventDebouncer.shouldEmit(debounceKey, eventTimeMs)) {
      return null
    }

    val event =
      EdgeEvent(
        eventId = eventIdGenerator.next(eventTimeMs),
        deviceCode = config.deviceCode,
        reportedEnterpriseId = config.enterpriseId,
        fleetId = config.fleetId,
        vehicleId = config.vehicleId,
        driverId = config.driverId,
        sessionId = config.sessionId,
        configVersion = config.configVersion,
        eventTimeUtc = Instant.ofEpochMilli(eventTimeMs).toString(),
        fatigueScore = candidate.fatigueScore.coerceIn(0.0, 1.0),
        distractionScore = candidate.distractionScore.coerceIn(0.0, 1.0),
        riskLevel = candidate.riskLevel,
        dominantRiskType = candidate.dominantRiskType,
        triggerReasons = candidate.triggerReasons,
        algorithmVer = config.algorithmVersion,
        uploadStatus = UploadStatus.PENDING,
        windowStartMs = candidate.windowStartMs,
        windowEndMs = candidate.windowEndMs,
        createdAtMs = clock.millis(),
      )

    eventStore.append(event)
    return event
  }

  private fun buildDebounceKey(candidate: RiskEventCandidate): String {
    val sortedReasons = candidate.triggerReasons.map { it.name }.sorted().joinToString("+")
    return listOf(
      candidate.riskLevel.name,
      candidate.dominantRiskType?.name ?: "UNKNOWN",
      sortedReasons,
    ).joinToString("|")
  }
}
