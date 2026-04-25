package com.driveedge.uploader

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Duration

interface EventsApiTransport {
  fun postEvent(
    endpointUrl: String,
    deviceToken: String,
    eventId: String,
    idempotencyHeaderName: String,
    eventIdHeaderName: String,
    requestBody: String,
    timeout: Duration,
  ): TransportResponse
}

data class TransportResponse(
  val statusCode: Int,
  val body: String,
)

open class TransportException(
  message: String,
  val failureCategory: UploadFailureCategory,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

class HttpEventsApiTransport(
  private val connectTimeout: Duration = Duration.ofSeconds(5),
) : EventsApiTransport {
  override fun postEvent(
    endpointUrl: String,
    deviceToken: String,
    eventId: String,
    idempotencyHeaderName: String,
    eventIdHeaderName: String,
    requestBody: String,
    timeout: Duration,
  ): TransportResponse {
    val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = connectTimeout.toTimeoutMs()
      connection.readTimeout = timeout.toTimeoutMs()
      connection.doOutput = true
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("X-Device-Token", deviceToken)
      connection.setRequestProperty(idempotencyHeaderName, eventId)
      connection.setRequestProperty(eventIdHeaderName, eventId)

      val bodyBytes = requestBody.toByteArray(StandardCharsets.UTF_8)
      connection.outputStream.use { output ->
        output.write(bodyBytes)
        output.flush()
      }

      val statusCode = connection.responseCode
      val stream =
        if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
          connection.errorStream
        } else {
          connection.inputStream
        }
      val body =
        stream?.bufferedReader(StandardCharsets.UTF_8).use { reader ->
          reader?.readText().orEmpty()
        }

      return TransportResponse(
        statusCode = statusCode,
        body = body,
      )
    } catch (error: SocketTimeoutException) {
      throw TransportException(
        message = error.message ?: "socket timeout",
        failureCategory = UploadFailureCategory.TIMEOUT,
        cause = error,
      )
    } catch (error: java.io.InterruptedIOException) {
      throw TransportException(
        message = error.message ?: "request timeout",
        failureCategory = UploadFailureCategory.TIMEOUT,
        cause = error,
      )
    } catch (error: java.io.IOException) {
      throw TransportException(
        message = error.message ?: "network error",
        failureCategory = UploadFailureCategory.NETWORK,
        cause = error,
      )
    } finally {
      connection.disconnect()
    }
  }
}

private fun Duration.toTimeoutMs(): Int = toMillis().coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
