package com.arcadence.worker

import scala.concurrent.duration.*

/** A single worker heartbeat proving the event loop is alive. Real job
  * processing lands in later phases.
  */
final case class Heartbeat(tick: Long, interval: FiniteDuration):
  def describe: String =
    s"worker heartbeat tick=$tick intervalMs=${interval.toMillis} status=up"

object Heartbeat:
  def next(previous: Heartbeat): Heartbeat =
    previous.copy(tick = previous.tick + 1)
