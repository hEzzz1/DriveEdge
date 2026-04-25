package com.driveedge.uploader

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.UploadStatus
import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventUploaderTest {
  @Test
  fun `upload posts event to driveserver and parses code and traceId`() {
    val transport =
      RecordingTransport(
        nextResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"code":0,"message":"ok","traceId":"trace-10001","data":{}}""",
          ),
      )
    val uploader =
      EventUploader(
        config = UploaderConfig(baseUrl = "https://driveserver.local/", deviceToken = "token-001"),
        transport = transport,
      )

    val receipt = uploader.upload(sampleEvent())

    assertEquals("https://driveserver.local/api/v1/events", transport.lastEndpointUrl)
    assertEquals("token-001", transport.lastDeviceToken)
    assertEquals("evt-1001", transport.lastEventId)
    assertEquals("Idempotency-Key", transport.lastIdempotencyHeaderName)
    assertEquals("X-Event-Id", transport.lastEventIdHeaderName)
    assertNotNull(transport.lastRequestBody)
    assertTrue(transport.lastRequestBody!!.contains("\"eventId\":\"evt-1001\""))
    assertTrue(transport.lastRequestBody!!.contains("\"eventTime\":\"2026-04-09T11:20:30Z\""))
    assertEquals(0, receipt.code)
    assertEquals("trace-10001", receipt.traceId)
    assertEquals("ok", receipt.message)
    assertEquals(200, receipt.httpStatus)
    assertFalse(receipt.isTransportFailure)
    assertEquals(UploadFailureCategory.NONE, receipt.failureCategory)
  }

  @Test
  fun `upload returns parse error receipt when response code is missing`() {
    val transport =
      RecordingTransport(
        nextResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"message":"bad response","traceId":"trace-missing-code"}""",
          ),
      )
    val uploader =
      EventUploader(
        config = UploaderConfig(baseUrl = "https://driveserver.local", deviceToken = "token-001"),
        transport = transport,
      )

    val receipt = uploader.upload(sampleEvent())

    assertEquals(UploadReceipt.RESPONSE_PARSE_ERROR_CODE, receipt.code)
    assertEquals("trace-missing-code", receipt.traceId)
    assertEquals(200, receipt.httpStatus)
    assertFalse(receipt.isTransportFailure)
    assertEquals(UploadFailureCategory.RESPONSE_PARSE, receipt.failureCategory)
  }

  @Test
  fun `upload returns transport error receipt when network call throws`() {
    val transport =
      RecordingTransport(
        nextError =
          TransportException(
            message = "socket timeout",
            failureCategory = UploadFailureCategory.TIMEOUT,
          ),
      )
    val uploader =
      EventUploader(
        config =
          UploaderConfig(
            baseUrl = "https://driveserver.local",
            deviceToken = "token-001",
            requestTimeout = Duration.ofSeconds(2),
          ),
        transport = transport,
      )

    val receipt = uploader.upload(sampleEvent())

    assertEquals(UploadReceipt.NETWORK_ERROR_CODE, receipt.code)
    assertNull(receipt.traceId)
    assertNull(receipt.httpStatus)
    assertTrue(receipt.isTransportFailure)
    assertEquals("socket timeout", receipt.transportError)
    assertEquals(UploadFailureCategory.TIMEOUT, receipt.failureCategory)
  }

  @Test
  fun `upload marks client and server responses with failure category`() {
    val clientTransport =
      RecordingTransport(
        nextResponse =
          TransportResponse(
            statusCode = 409,
            body = """{"code":40901,"message":"conflict","traceId":"trace-client"}""",
          ),
      )
    val serverTransport =
      RecordingTransport(
        nextResponse =
          TransportResponse(
            statusCode = 500,
            body = """{"code":50001,"message":"busy","traceId":"trace-server"}""",
          ),
      )

    val clientReceipt = EventUploader(UploaderConfig("https://driveserver.local", "token-001"), clientTransport).upload(sampleEvent())
    val serverReceipt = EventUploader(UploaderConfig("https://driveserver.local", "token-001"), serverTransport).upload(sampleEvent())

    assertEquals(UploadFailureCategory.CLIENT, clientReceipt.failureCategory)
    assertEquals(UploadFailureCategory.SERVER, serverReceipt.failureCategory)
  }

  private fun sampleEvent(): EdgeEvent =
    EdgeEvent(
      eventId = "evt-1001",
      fleetId = "fleet-001",
      vehicleId = "VEH-1001",
      driverId = "DRV-88",
      eventTimeUtc = "2026-04-09T11:20:30Z",
      fatigueScore = 0.82,
      distractionScore = 0.31,
      riskLevel = RiskLevel.HIGH,
      dominantRiskType = RiskType.FATIGUE,
      triggerReasons =
        setOf(
          TriggerReason.FATIGUE_PERCLOS_SUSTAINED,
          TriggerReason.FATIGUE_YAWN_FREQUENT,
        ),
      algorithmVer = "yolo-v8n-int8-20260407",
      uploadStatus = UploadStatus.PENDING,
      windowStartMs = 1_710_000_000_000L,
      windowEndMs = 1_710_000_003_000L,
      createdAtMs = 1_710_000_003_100L,
    )

  private class RecordingTransport(
    private val nextResponse: TransportResponse? = null,
    private val nextError: RuntimeException? = null,
  ) : EventsApiTransport {
    var lastEndpointUrl: String? = null
      private set
    var lastDeviceToken: String? = null
      private set
    var lastEventId: String? = null
      private set
    var lastIdempotencyHeaderName: String? = null
      private set
    var lastEventIdHeaderName: String? = null
      private set
    var lastRequestBody: String? = null
      private set
    var lastTimeoutMs: Long? = null
      private set

    override fun postEvent(
      endpointUrl: String,
      deviceToken: String,
      eventId: String,
      idempotencyHeaderName: String,
      eventIdHeaderName: String,
      requestBody: String,
      timeout: Duration,
    ): TransportResponse {
      lastEndpointUrl = endpointUrl
      lastDeviceToken = deviceToken
      lastEventId = eventId
      lastIdempotencyHeaderName = idempotencyHeaderName
      lastEventIdHeaderName = eventIdHeaderName
      lastRequestBody = requestBody
      lastTimeoutMs = timeout.toMillis()

      nextError?.let { throw it }
      return nextResponse ?: error("nextResponse must be provided when nextError is null")
    }
  }
}
