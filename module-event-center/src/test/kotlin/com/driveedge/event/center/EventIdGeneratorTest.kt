package com.driveedge.event.center

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventIdGeneratorTest {
  @Test
  fun `next follows expected format and sequence`() {
    val generator = EventIdGenerator(vehicleId = "VEH-1001")
    val t1 = Instant.parse("2026-04-09T08:10:11.120Z").toEpochMilli()
    val t2 = Instant.parse("2026-04-09T08:10:11.980Z").toEpochMilli()
    val t3 = Instant.parse("2026-04-09T08:10:12.001Z").toEpochMilli()

    val id1 = generator.next(t1)
    val id2 = generator.next(t2)
    val id3 = generator.next(t3)

    assertEquals("evt_VEH-1001_20260409081011_0001", id1)
    assertEquals("evt_VEH-1001_20260409081011_0002", id2)
    assertEquals("evt_VEH-1001_20260409081012_0001", id3)
    assertTrue(id1.startsWith("evt_"))
  }
}
