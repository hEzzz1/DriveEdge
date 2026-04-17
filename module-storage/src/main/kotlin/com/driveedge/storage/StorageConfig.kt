package com.driveedge.storage

data class StorageConfig
  @JvmOverloads
  constructor(
  val maxRetryCount: Int = 10,
  val defaultBatchSize: Int = 50,
  val retryBackoffPolicy: RetryBackoffPolicy = RetryBackoffPolicy(),
) 

class RetryBackoffPolicy
  @JvmOverloads
  constructor(
  private val scheduleMs: List<Long> = DEFAULT_SCHEDULE_MS,
  private val maxBackoffMs: Long = 120_000L,
) {
  fun delayMsForAttempt(retryCount: Int): Long {
    if (retryCount <= 0) {
      return 0L
    }
    val scheduled = scheduleMs.getOrNull(retryCount - 1) ?: maxBackoffMs
    return scheduled.coerceAtMost(maxBackoffMs).coerceAtLeast(0L)
  }

  private companion object {
    val DEFAULT_SCHEDULE_MS: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
  }
}
