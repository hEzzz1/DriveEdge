package com.driveedge.event.center

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class FileEdgeEventStore(
  private val filePath: Path,
) : EdgeEventStore {
  init {
    filePath.parent?.let { Files.createDirectories(it) }
  }

  @Synchronized
  override fun append(event: EdgeEvent) {
    val line = encode(event)
    Files.writeString(filePath, "$line\n", CREATE, WRITE, APPEND)
  }

  private fun encode(event: EdgeEvent): String =
    listOf(
      event.eventId,
      event.fleetId.orEmpty(),
      event.vehicleId,
      event.driverId.orEmpty(),
      event.eventTimeUtc,
      event.fatigueScore.toString(),
      event.distractionScore.toString(),
      event.riskLevel.name,
      event.dominantRiskType?.name.orEmpty(),
      event.triggerReasons.map { it.name }.sorted().joinToString(","),
      event.algorithmVer,
      event.uploadStatus.name,
      event.windowStartMs.toString(),
      event.windowEndMs.toString(),
      event.createdAtMs.toString(),
    ).joinToString("\t", transform = ::escape)

  private fun escape(value: String): String =
    value.replace("\\", "\\\\")
      .replace("\t", "\\t")
      .replace("\n", "\\n")
}
