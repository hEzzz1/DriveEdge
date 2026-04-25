package com.driveedge.storage

data class StorageConfig
  @JvmOverloads
  constructor(
  val maxRetryCount: Int = 10,
  val defaultBatchSize: Int = 50,
  val maxBatchSize: Int = 100,
  val retryBackoffPolicy: RetryBackoffPolicy = RetryBackoffPolicy(),
) {
  init {
    require(maxRetryCount > 0) { "maxRetryCount must be > 0" }
    require(defaultBatchSize > 0) { "defaultBatchSize must be > 0" }
    require(maxBatchSize >= defaultBatchSize) { "maxBatchSize must be >= defaultBatchSize" }
  }
}

class RetryBackoffPolicy
  @JvmOverloads
  constructor(
  private val scheduleMs: List<Long> = DEFAULT_SCHEDULE_MS,
  private val maxBackoffMs: Long = 120_000L,
  private val jitterUpperBoundMs: Long = 2_000L,
) {
  fun delayMsForAttempt(
    retryCount: Int,
    eventId: String = "",
  ): Long {
    if (retryCount <= 0) {
      return 0L
    }
    val scheduled = scheduleMs.getOrNull(retryCount - 1) ?: maxBackoffMs
    val baseDelayMs = scheduled.coerceAtMost(maxBackoffMs).coerceAtLeast(0L)
    val jitterMs =
      if (jitterUpperBoundMs <= 0L) {
        0L
      } else {
        stableJitterMs(eventId = eventId, retryCount = retryCount, upperBoundMs = jitterUpperBoundMs)
      }
    return (baseDelayMs + jitterMs).coerceAtMost(maxBackoffMs + jitterUpperBoundMs).coerceAtLeast(0L)
  }

  private fun stableJitterMs(
    eventId: String,
    retryCount: Int,
    upperBoundMs: Long,
  ): Long {
    val seed = (31L * retryCount) + eventId.hashCode().toLong()
    val positive = seed and Long.MAX_VALUE
    return positive % (upperBoundMs + 1L)
  }

  private companion object {
    val DEFAULT_SCHEDULE_MS: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
  }
}
