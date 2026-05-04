package com.driveedge.uploader

import com.driveedge.event.center.EdgeEvent
import com.driveedge.event.center.EdgeEventEvidence
import com.driveedge.event.center.UploadStatus
import com.driveedge.risk.engine.RiskLevel
import com.driveedge.risk.engine.RiskType
import com.driveedge.risk.engine.TriggerReason
import java.io.File
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
        config = UploaderConfig(baseUrl = "https://driveserver.local/", deviceCode = "DEV-001", deviceToken = "token-001"),
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
  fun `upload sends evidence first and replaces evidence url with server path`() {
    val transport =
      RecordingTransport(
        nextEvidenceResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"code":0,"message":"ok","traceId":"trace-evidence","data":{"evidenceUrl":"alert-evidence:events/evt-1001.jpg","evidenceMimeType":"image/jpeg","evidenceType":"KEY_FRAME","evidenceCapturedAtMs":1710000000000}}""",
          ),
        nextResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"code":0,"message":"ok","traceId":"trace-10002","data":{}}""",
          ),
      )
    val uploader =
      EventUploader(
        config =
          UploaderConfig(
            baseUrl = "https://driveserver.local/",
            deviceCode = "DEV-001",
            deviceToken = "token-001",
          ),
        transport = transport,
      )

    val receipt = uploader.upload(sampleEvent(withEvidence = true))

    assertEquals("https://driveserver.local/api/v1/events/evidence", transport.lastEvidenceEndpointUrl)
    assertEquals("trace-10002", receipt.traceId)
    assertEquals(0, receipt.code)
    assertNotNull(transport.lastEvidenceBodyBytes)
    assertTrue(transport.lastRequestBody!!.contains("\"evidenceUrl\":\"alert-evidence:events/evt-1001.jpg\""))
  }

  @Test
  fun `upload sends zip evidence from local file`() {
    val evidenceFile = File.createTempFile("driveedge-sequence", ".zip")
    evidenceFile.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    val transport =
      RecordingTransport(
        nextEvidenceResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"code":0,"message":"ok","data":{"evidenceUrl":"alert-evidence:events/evt-1001.zip","evidenceMimeType":"application/zip","evidenceType":"FRAME_SEQUENCE","evidenceCapturedAtMs":1710000003050}}""",
          ),
        nextResponse =
          TransportResponse(
            statusCode = 200,
            body = """{"code":0,"message":"ok","traceId":"trace-zip","data":{}}""",
          ),
      )
    val uploader =
      EventUploader(
        config = UploaderConfig(baseUrl = "https://driveserver.local/", deviceCode = "DEV-001", deviceToken = "token-001"),
        transport = transport,
      )

    try {
      val receipt = uploader.upload(sampleEvent(withZipEvidenceFile = evidenceFile))

      assertEquals(0, receipt.code)
      assertEquals("application/zip", transport.lastEvidenceMimeType)
      assertEquals(evidenceFile.name, transport.lastEvidenceFilename)
      assertTrue(transport.lastEvidenceBodyBytes!!.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
      assertEquals("evt-1001", transport.lastEventId)
      assertTrue(transport.lastEndpointUrl!!.endsWith("/api/v1/events"))
      assertTrue(transport.lastEvidenceEndpointUrl!!.endsWith("/api/v1/events/evidence"))
      assertTrue(transport.lastRequestBody!!.contains("\"evidenceUrl\":\"alert-evidence:events/evt-1001.zip\""))
    } finally {
      evidenceFile.delete()
    }
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
        config = UploaderConfig(baseUrl = "https://driveserver.local", deviceCode = "DEV-001", deviceToken = "token-001"),
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
            deviceCode = "DEV-001",
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

    val clientReceipt = EventUploader(UploaderConfig("https://driveserver.local", "DEV-001", "token-001"), clientTransport).upload(sampleEvent())
    val serverReceipt = EventUploader(UploaderConfig("https://driveserver.local", "DEV-001", "token-001"), serverTransport).upload(sampleEvent())

    assertEquals(UploadFailureCategory.CLIENT, clientReceipt.failureCategory)
    assertEquals(UploadFailureCategory.SERVER, serverReceipt.failureCategory)
  }

  private fun sampleEvent(): EdgeEvent =
    sampleEvent(withEvidence = false)

  private fun sampleEvent(withEvidence: Boolean): EdgeEvent =
    sampleEvent(withEvidence = withEvidence, withZipEvidenceFile = null)

  private fun sampleEvent(withZipEvidenceFile: File): EdgeEvent =
    sampleEvent(withEvidence = false, withZipEvidenceFile = withZipEvidenceFile)

  private fun sampleEvent(
    withEvidence: Boolean,
    withZipEvidenceFile: File?,
  ): EdgeEvent =
    EdgeEvent(
      eventId = "evt-1001",
      deviceCode = "DEV-001",
      reportedEnterpriseId = "100",
      fleetId = "fleet-001",
      vehicleId = "VEH-1001",
      driverId = "DRV-88",
      sessionId = 9001L,
      configVersion = "ruleset/1/1/1",
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
      evidence =
        when {
          withZipEvidenceFile != null ->
            EdgeEventEvidence(
              type = "FRAME_SEQUENCE",
              url = withZipEvidenceFile.toURI().toString(),
              mimeType = "application/zip",
              capturedAtMs = 1_710_000_003_050L,
            )
          withEvidence ->
            EdgeEventEvidence(
              type = "KEY_FRAME",
              url = "data:image/jpeg;base64,ZmFrZS1qcGVn",
              mimeType = "image/jpeg",
              capturedAtMs = 1_710_000_003_050L,
            )
          else -> null
        },
    )

  private class RecordingTransport(
    private val nextResponse: TransportResponse? = null,
    private val nextEvidenceResponse: TransportResponse? = null,
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
    var lastEvidenceEndpointUrl: String? = null
      private set
    var lastEvidenceBodyBytes: ByteArray? = null
      private set
    var lastEvidenceMimeType: String? = null
      private set
    var lastEvidenceFilename: String? = null
      private set
    var lastTimeoutMs: Long? = null
      private set

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
      lastEvidenceEndpointUrl = endpointUrl
      lastDeviceToken = deviceToken
      lastEventId = eventId
      lastEvidenceFilename = filename
      lastEvidenceMimeType = evidenceMimeType
      lastEvidenceBodyBytes = bytes
      lastTimeoutMs = timeout.toMillis()

      nextError?.let { throw it }
      return nextEvidenceResponse ?: error("nextEvidenceResponse must be provided when nextError is null")
    }
  }
}
