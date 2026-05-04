package com.driveedge.uploader

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.EdgeEventEvidence
import java.io.File
import java.net.URI
import java.util.Base64

class EventUploader
  @JvmOverloads
  constructor(
    private val config: UploaderConfig,
    private val transport: EventsApiTransport = HttpEventsApiTransport(connectTimeout = config.connectTimeout),
  ) {
  fun upload(event: EdgeEvent): UploadReceipt {
    return try {
      val preparedEvent = uploadEvidenceIfNeeded(event)
      val payload = EventPayloadMapper.toJson(preparedEvent)
      val response =
        transport.postEvent(
          endpointUrl = config.endpointUrl(),
          deviceCode = config.deviceCode,
          deviceToken = config.deviceToken,
          eventId = event.eventId,
          idempotencyHeaderName = config.idempotencyHeaderName,
          eventIdHeaderName = config.eventIdHeaderName,
          requestBody = payload,
          timeout = config.requestTimeout,
        )
      toReceipt(eventId = event.eventId, response = response)
    } catch (error: EvidenceUploadFailure) {
      error.receipt
    } catch (error: TransportException) {
      UploadReceipt(
        eventId = event.eventId,
        code = UploadReceipt.NETWORK_ERROR_CODE,
        traceId = null,
        message = error.message ?: error.javaClass.simpleName,
        httpStatus = null,
        responseBody = null,
        transportError = error.message ?: error.javaClass.simpleName,
        failureCategory = error.failureCategory,
      )
    } catch (error: Exception) {
      UploadReceipt(
        eventId = event.eventId,
        code = UploadReceipt.NETWORK_ERROR_CODE,
        traceId = null,
        message = error.message ?: error.javaClass.simpleName,
        httpStatus = null,
        responseBody = null,
        transportError = error.message ?: error.javaClass.simpleName,
        failureCategory = UploadFailureCategory.UNKNOWN,
      )
    }
  }

  private fun uploadEvidenceIfNeeded(event: EdgeEvent): EdgeEvent {
    val evidence = event.evidence ?: return event
    val source =
      try {
        EvidencePayloadSource.from(evidence, config.maxEvidenceBytes)
      } catch (error: IllegalArgumentException) {
        throw EvidenceUploadFailure(
          UploadReceipt(
            eventId = event.eventId,
            code = 40001,
            traceId = null,
            message = error.message ?: "invalid evidence payload",
            httpStatus = null,
            responseBody = null,
            transportError = error.message ?: "invalid evidence payload",
            failureCategory = UploadFailureCategory.CLIENT,
          ),
        )
      } ?: return event

    val response =
      transport.postEvidence(
        endpointUrl = config.evidenceEndpointUrl(),
        deviceCode = config.deviceCode,
        deviceToken = config.deviceToken,
        eventId = event.eventId,
        evidenceType = evidence.type,
        evidenceMimeType = source.mimeType,
        evidenceCapturedAtMs = evidence.capturedAtMs,
        filename = source.filename,
        bytes = source.bytes,
        timeout = config.requestTimeout,
      )
    val receipt = toReceipt(event.eventId, response)
    if (receipt.code != 0) {
      throw EvidenceUploadFailure(receipt)
    }
    val serverEvidenceUrl = UnifiedResponseParser.parseStringField(response.body, "evidenceUrl")
    val serverEvidenceMimeType = UnifiedResponseParser.parseStringField(response.body, "evidenceMimeType")
    val serverEvidenceType = UnifiedResponseParser.parseStringField(response.body, "evidenceType")
    val serverCapturedAtMs = UnifiedResponseParser.parseLongField(response.body, "evidenceCapturedAtMs")
    return event.copy(
      evidence =
        evidence.copy(
          url = serverEvidenceUrl ?: evidence.url,
          mimeType = serverEvidenceMimeType ?: evidence.mimeType,
          type = serverEvidenceType ?: evidence.type,
          capturedAtMs = serverCapturedAtMs ?: evidence.capturedAtMs,
        ),
    )
  }

  private fun toReceipt(
    eventId: String,
    response: TransportResponse,
  ): UploadReceipt {
    val code = UnifiedResponseParser.parseIntField(response.body, "code")
    val traceId = UnifiedResponseParser.parseStringField(response.body, "traceId")
    val message = UnifiedResponseParser.parseStringField(response.body, "message")

    if (code == null) {
      return UploadReceipt(
        eventId = eventId,
        code = UploadReceipt.RESPONSE_PARSE_ERROR_CODE,
        traceId = traceId,
        message = message ?: "Unable to parse response code",
        httpStatus = response.statusCode,
        responseBody = response.body,
        transportError = null,
        failureCategory = UploadFailureCategory.RESPONSE_PARSE,
      )
    }

    return UploadReceipt(
      eventId = eventId,
      code = code,
      traceId = traceId,
      message = message,
      httpStatus = response.statusCode,
      responseBody = response.body,
      transportError = null,
      failureCategory =
        when {
          response.statusCode >= 500 -> UploadFailureCategory.SERVER
          response.statusCode >= 400 -> UploadFailureCategory.CLIENT
          else -> UploadFailureCategory.NONE
        },
    )
  }
}

private class EvidenceUploadFailure(
  val receipt: UploadReceipt,
) : RuntimeException(receipt.message)

private data class EvidencePayloadSource(
  val mimeType: String,
  val filename: String,
  val bytes: ByteArray,
) {
  companion object {
    fun from(
      evidence: EdgeEventEvidence,
      maxBytes: Long,
    ): EvidencePayloadSource? {
      val url = evidence.url.trim()
      if (url.startsWith("data:", ignoreCase = true)) {
        return fromDataUri(url, evidence.mimeType, maxBytes)
      }
      if (url.startsWith("file:", ignoreCase = true)) {
        return fromFilePath(URI(url), evidence.mimeType, maxBytes)
      }
      if (url.startsWith("/")) {
        return fromFilePath(File(url).toURI(), evidence.mimeType, maxBytes)
      }
      return null
    }

    private fun fromDataUri(
      value: String,
      fallbackMimeType: String,
      maxBytes: Long,
    ): EvidencePayloadSource {
      val commaIndex = value.indexOf(',')
      require(commaIndex > 5) { "invalid data uri" }
      val metadata = value.substring(5, commaIndex)
      val data = value.substring(commaIndex + 1)
      var mimeType: String? = null
      var base64Encoded = false
      metadata.split(';').forEach { token ->
        when {
          token.equals("base64", ignoreCase = true) -> base64Encoded = true
          token.contains('/') -> mimeType = token.trim().lowercase()
        }
      }
      val normalizedMimeType = (mimeType ?: fallbackMimeType).ifBlank { "application/octet-stream" }.lowercase()
      val bytes =
        if (base64Encoded) {
          Base64.getDecoder().decode(data)
        } else {
          data.toByteArray(Charsets.UTF_8)
        }
      require(bytes.size.toLong() <= maxBytes) { "evidence too large" }
      return EvidencePayloadSource(
        mimeType = normalizedMimeType,
        filename = "evidence.${extensionFor(normalizedMimeType)}",
        bytes = bytes,
      )
    }

    private fun fromFilePath(
      uri: URI,
      fallbackMimeType: String,
      maxBytes: Long,
    ): EvidencePayloadSource? {
      val file = File(uri)
      if (!file.exists() || !file.isFile) {
        return null
      }
      val bytes = file.readBytes()
      require(bytes.size.toLong() <= maxBytes) { "evidence too large" }
      val mimeType = fallbackMimeType.ifBlank { "application/octet-stream" }.lowercase()
      return EvidencePayloadSource(
        mimeType = mimeType,
        filename = file.name.ifBlank { "evidence.${extensionFor(mimeType)}" },
        bytes = bytes,
      )
    }

    private fun extensionFor(mimeType: String): String =
      when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "application/zip" -> "zip"
        "application/x-zip-compressed" -> "zip"
        else -> "bin"
      }
  }
}

private object EventPayloadMapper {
  fun toJson(event: EdgeEvent): String {
    val fields = linkedMapOf<String, String>()
    fields["eventId"] = event.eventId.toJsonString()
    fields["deviceCode"] = event.deviceCode.toJsonString()
    fields["vehicleId"] = event.vehicleId.toJsonString()
    fields["eventTime"] = event.eventTimeUtc.toJsonString()
    fields["fatigueScore"] = event.fatigueScore.toString()
    fields["distractionScore"] = event.distractionScore.toString()
    fields["algorithmVer"] = event.algorithmVer.toJsonString()

    event.reportedEnterpriseId?.let { fields["reportedEnterpriseId"] = it.toJsonString() }
    event.fleetId?.let { fields["fleetId"] = it.toJsonString() }
    event.driverId?.let { fields["driverId"] = it.toJsonString() }
    event.sessionId?.let { fields["sessionId"] = it.toString() }
    event.configVersion?.let { fields["configVersion"] = it.toJsonString() }
    fields["riskLevel"] = event.riskLevel.name.toJsonString()
    event.dominantRiskType?.let { fields["dominantRiskType"] = it.name.toJsonString() }
    if (event.triggerReasons.isNotEmpty()) {
      fields["triggerReasons"] = event.triggerReasons.joinToString(prefix = "[", postfix = "]") { it.name.toJsonString() }
    }
    fields["windowStartMs"] = event.windowStartMs.toString()
    fields["windowEndMs"] = event.windowEndMs.toString()
    fields["createdAtMs"] = event.createdAtMs.toString()
    event.evidence?.let { evidence ->
      fields["evidenceType"] = evidence.type.toJsonString()
      fields["evidenceUrl"] = evidence.url.toJsonString()
      fields["evidenceMimeType"] = evidence.mimeType.toJsonString()
      fields["evidenceCapturedAtMs"] = evidence.capturedAtMs.toString()
    }

    return buildString {
      append('{')
      append(fields.entries.joinToString(separator = ",") { (name, value) -> "\"$name\":$value" })
      append('}')
    }
  }

  private fun String.toJsonString(): String = "\"${escapeJson()}\""

  private fun String.escapeJson(): String =
    buildString(length + 8) {
      this@escapeJson.forEach { ch ->
        when (ch) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\b' -> append("\\b")
          '\u000C' -> append("\\f")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> {
            if (ch.code < 0x20) {
              append("\\u")
              append(ch.code.toString(16).padStart(4, '0'))
            } else {
              append(ch)
            }
          }
        }
      }
    }
}

private object UnifiedResponseParser {
  fun parseIntField(
    body: String,
    fieldName: String,
  ): Int? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(-?\\d+)")
    return regex.find(body)?.groupValues?.get(1)?.toIntOrNull()
  }

  fun parseLongField(
    body: String,
    fieldName: String,
  ): Long? {
    val regex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(-?\\d+)")
    return regex.find(body)?.groupValues?.get(1)?.toLongOrNull()
  }

  fun parseStringField(
    body: String,
    fieldName: String,
  ): String? {
    val quotedRegex = Regex("\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\"])*)\\\"")
    val value = quotedRegex.find(body)?.groupValues?.get(1) ?: return null
    return unescapeJsonString(value)
  }

  private fun unescapeJsonString(raw: String): String {
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
      val ch = raw[index]
      if (ch != '\\' || index == raw.length - 1) {
        out.append(ch)
        index += 1
        continue
      }

      val escaped = raw[index + 1]
      when (escaped) {
        '"' -> out.append('"')
        '\\' -> out.append('\\')
        '/' -> out.append('/')
        'b' -> out.append('\b')
        'f' -> out.append('\u000C')
        'n' -> out.append('\n')
        'r' -> out.append('\r')
        't' -> out.append('\t')
        'u' -> {
          if (index + 5 < raw.length) {
            val code = raw.substring(index + 2, index + 6).toIntOrNull(16)
            if (code != null) {
              out.append(code.toChar())
              index += 6
              continue
            }
          }
          out.append('u')
        }

        else -> out.append(escaped)
      }
      index += 2
    }
    return out.toString()
  }
}
