package com.driveedge.uploader

enum class UploadFailureCategory {
  NONE,
  NETWORK,
  TIMEOUT,
  SERVER,
  CLIENT,
  RESPONSE_PARSE,
  UNKNOWN,
}

data class UploadReceipt(
  val eventId: String,
  val code: Int,
  val traceId: String?,
  val message: String?,
  val httpStatus: Int?,
  val responseBody: String?,
  val transportError: String?,
  val failureCategory: UploadFailureCategory = UploadFailureCategory.NONE,
) {
  val isTransportFailure: Boolean
    get() = transportError != null

  companion object {
    const val NETWORK_ERROR_CODE: Int = -1
    const val RESPONSE_PARSE_ERROR_CODE: Int = -2
  }
}
