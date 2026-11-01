package com.arcadence.core.webhooks

import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Webhook delivery (Phase 12.1): domain events are dispatched to registered
  * subscriber URLs as HMAC-SHA256-signed JSON payloads, with bounded
  * exponential-backoff retries and dead-lettering. Replay resets a dead
  * delivery for a fresh attempt.
  */
object Webhooks:

  val maxAttempts = 5
  val backoffSeconds: Int => Long = {
    case 1 => 60L
    case 2 => 300L
    case 3 => 1800L
    case 4 => 7200L
    case _ => 86400L
  }

  /** HMAC-SHA256 hex signature over "timestamp.payload" (Stripe-style). */
  def sign(secret: String, timestampSeconds: Long, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac
      .doFinal(s"$timestampSeconds.$payload".getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  def signatureHeader(
      secret: String,
      timestampSeconds: Long,
      payload: String
  ): String =
    s"t=$timestampSeconds,v1=${sign(secret, timestampSeconds, payload)}"

  /** Standard envelope for every delivery. */
  def envelope(event: String, workspaceId: String, data: Json): Json =
    Json.obj(
      "event" -> event.asJson,
      "workspaceId" -> workspaceId.asJson,
      "data" -> data,
      "sentAt" -> java.time.Instant.now().toString.asJson
    )
