package com.driveedge.uploader

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Duration

interface EventsApiTransport {
  fun postEvent(
    endpointUrl: String,
    deviceCode: String,
    deviceToken: String,
    eventId: String,
    idempotencyHeaderName: String,
    eventIdHeaderName: String,
    requestBody: String,
    timeout: Duration,
  ): TransportResponse

  fun postEvidence(
    endpointUrl: String,
    deviceCode: String,
    deviceToken: String,
    eventId: String,
    evidenceType: String,
    evidenceMimeType: String,
    evidenceCapturedAtMs: Long,
    filename: String,
    bytes: ByteArray,
    timeout: Duration,
  ): TransportResponse {
    throw TransportException(
      message = "evidence upload is not supported by this transport",
      failureCategory = UploadFailureCategory.UNKNOWN,
    )
  }
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
    deviceCode: String,
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
      connection.setRequestProperty("X-Device-Code", deviceCode)
      connection.setRequestProperty("X-Device-Token", deviceToken)
      connection.setRequestProperty(idempotencyHeaderName, eventId)
      connection.setRequestProperty(eventIdHeaderName, eventId)

      val bodyBytes = requestBody.toByteArray(StandardCharsets.UTF_8)
      connection.outputStream.use { output ->
        output.write(bodyBytes)
        output.flush()
      }

      return readResponse(connection)
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

  override fun postEvidence(
    endpointUrl: String,
    deviceCode: String,
    deviceToken: String,
    eventId: String,
    evidenceType: String,
    evidenceMimeType: String,
    evidenceCapturedAtMs: Long,
    filename: String,
    bytes: ByteArray,
    timeout: Duration,
  ): TransportResponse {
    val boundary = "DriveEdgeBoundary${System.nanoTime()}"
    val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = connectTimeout.toTimeoutMs()
      connection.readTimeout = timeout.toTimeoutMs()
      connection.doOutput = true
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("X-Device-Code", deviceCode)
      connection.setRequestProperty("X-Device-Token", deviceToken)

      connection.outputStream.use { output ->
        writeMultipartField(output, boundary, "eventId", eventId)
        writeMultipartField(output, boundary, "evidenceType", evidenceType)
        writeMultipartField(output, boundary, "evidenceMimeType", evidenceMimeType)
        writeMultipartField(output, boundary, "evidenceCapturedAtMs", evidenceCapturedAtMs.toString())
        writeMultipartFile(output, boundary, "file", filename, evidenceMimeType, bytes)
        output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
      }

      return readResponse(connection)
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

  private fun readResponse(connection: HttpURLConnection): TransportResponse {
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
  }

  private fun writeMultipartField(
    output: java.io.OutputStream,
    boundary: String,
    name: String,
    value: String,
  ) {
    output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write(value.toByteArray(StandardCharsets.UTF_8))
    output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
  }

  private fun writeMultipartFile(
    output: java.io.OutputStream,
    boundary: String,
    name: String,
    filename: String,
    contentType: String,
    bytes: ByteArray,
  ) {
    output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write(
      (
        "Content-Disposition: form-data; name=\"$name\"; filename=\"${filename.escapeMultipartFilename()}\"\r\n" +
          "Content-Type: $contentType\r\n\r\n"
      ).toByteArray(StandardCharsets.UTF_8),
    )
    output.write(bytes)
    output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
  }
}

private fun Duration.toTimeoutMs(): Int = toMillis().coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun String.escapeMultipartFilename(): String =
  buildString(length) {
    this@escapeMultipartFilename.forEach { ch ->
      when (ch) {
        '"', '\r', '\n' -> append('_')
        else -> append(ch)
      }
    }
  }
