package com.arcadence.worker

import munit.FunSuite

import scala.concurrent.duration.*

final class HeartbeatSpec extends FunSuite:

  test("next increments the tick counter") {
    val first = Heartbeat(1, 30.seconds)
    assertEquals(Heartbeat.next(first), Heartbeat(2, 30.seconds))
    assertEquals(
      Heartbeat.next(Heartbeat.next(first)),
      Heartbeat(3, 30.seconds)
    )
  }

  test("describe reports the tick, interval and up status") {
    val described = Heartbeat(7, 5.seconds).describe
    assert(described.contains("tick=7"))
    assert(described.contains("intervalMs=5000"))
    assert(described.contains("status=up"))
  }
