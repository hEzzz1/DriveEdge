package com.driveedge.event.center

fun interface EdgeEventStore {
  fun append(event: EdgeEvent)
}
