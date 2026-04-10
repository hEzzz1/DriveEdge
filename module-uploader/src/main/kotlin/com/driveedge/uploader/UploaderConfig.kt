package com.driveedge.uploader

import java.time.Duration

data class UploaderConfig(
  val baseUrl: String,
  val deviceToken: String,
  val endpointPath: String = "/api/v1/events",
  val connectTimeout: Duration = Duration.ofSeconds(5),
  val requestTimeout: Duration = Duration.ofSeconds(8),
) {
  init {
    require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
    require(deviceToken.isNotBlank()) { "deviceToken must not be blank" }
    require(endpointPath.isNotBlank()) { "endpointPath must not be blank" }
    require(connectTimeout > Duration.ZERO) { "connectTimeout must be > 0" }
    require(requestTimeout > Duration.ZERO) { "requestTimeout must be > 0" }
  }

  fun endpointUrl(): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val normalizedPath = if (endpointPath.startsWith('/')) endpointPath else "/$endpointPath"
    return "$normalizedBase$normalizedPath"
  }
}
