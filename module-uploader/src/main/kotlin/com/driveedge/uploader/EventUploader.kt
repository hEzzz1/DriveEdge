package com.driveedge.uploader

import com.driveedge.event.center.EdgeEvent

class EventUploader
  @JvmOverloads
  constructor(
  private val config: UploaderConfig,
  private val transport: EventsApiTransport = HttpEventsApiTransport(connectTimeout = config.connectTimeout),
) {
  fun upload(event: EdgeEvent): UploadReceipt {
    val payload = EventPayloadMapper.toJson(event)
    return try {
      val response =
        transport.postEvent(
          endpointUrl = config.endpointUrl(),
          deviceToken = config.deviceToken,
          requestBody = payload,
          timeout = config.requestTimeout,
        )
      toReceipt(eventId = event.eventId, response = response)
    } catch (error: Exception) {
      UploadReceipt(
        eventId = event.eventId,
        code = UploadReceipt.NETWORK_ERROR_CODE,
        traceId = null,
        message = error.message ?: error.javaClass.simpleName,
        httpStatus = null,
        responseBody = null,
        transportError = error.message ?: error.javaClass.simpleName,
      )
    }
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
    )
  }
}

private object EventPayloadMapper {
  fun toJson(event: EdgeEvent): String {
    val fields = linkedMapOf<String, String>()
    fields["eventId"] = event.eventId.toJsonString()
    fields["vehicleId"] = event.vehicleId.toJsonString()
    fields["eventTime"] = event.eventTimeUtc.toJsonString()
    fields["fatigueScore"] = event.fatigueScore.toString()
    fields["distractionScore"] = event.distractionScore.toString()
    fields["algorithmVer"] = event.algorithmVer.toJsonString()

    event.fleetId?.let { fields["fleetId"] = it.toJsonString() }
    event.driverId?.let { fields["driverId"] = it.toJsonString() }

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
