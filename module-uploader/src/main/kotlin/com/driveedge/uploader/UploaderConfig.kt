package com.driveedge.uploader

import java.time.Duration

data class UploaderConfig
  @JvmOverloads
  constructor(
  val baseUrl: String,
  val deviceCode: String,
  val deviceToken: String,
  val endpointPath: String = "/api/v1/events",
  val evidenceEndpointPath: String = "/api/v1/events/evidence",
  val idempotencyHeaderName: String = "Idempotency-Key",
  val eventIdHeaderName: String = "X-Event-Id",
  val connectTimeout: Duration = Duration.ofSeconds(5),
  val requestTimeout: Duration = Duration.ofSeconds(8),
  val maxEvidenceBytes: Long = 20L * 1024L * 1024L,
) {
  init {
    require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
    require(deviceCode.isNotBlank()) { "deviceCode must not be blank" }
    require(deviceToken.isNotBlank()) { "deviceToken must not be blank" }
    require(endpointPath.isNotBlank()) { "endpointPath must not be blank" }
    require(evidenceEndpointPath.isNotBlank()) { "evidenceEndpointPath must not be blank" }
    require(idempotencyHeaderName.isNotBlank()) { "idempotencyHeaderName must not be blank" }
    require(eventIdHeaderName.isNotBlank()) { "eventIdHeaderName must not be blank" }
    require(connectTimeout > Duration.ZERO) { "connectTimeout must be > 0" }
    require(requestTimeout > Duration.ZERO) { "requestTimeout must be > 0" }
    require(maxEvidenceBytes > 0L) { "maxEvidenceBytes must be > 0" }
  }

  fun endpointUrl(): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val normalizedPath = if (endpointPath.startsWith('/')) endpointPath else "/$endpointPath"
    return "$normalizedBase$normalizedPath"
  }

  fun evidenceEndpointUrl(): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val normalizedPath = if (evidenceEndpointPath.startsWith('/')) evidenceEndpointPath else "/$evidenceEndpointPath"
    return "$normalizedBase$normalizedPath"
  }
}
