package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.webhooks.Webhooks
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*

import java.util.UUID
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.time.Duration
import scala.concurrent.duration.*

/** Dispatches webhook deliveries: claims due pending deliveries, POSTs the
  * signed envelope, and records the outcome. Failed attempts back off
  * exponentially (bounded) and dead-letter after max attempts; replay resets a
  * delivery to pending.
  */
final class WebhookDispatcher(
    xa: Transactor[IO],
    httpClient: Client[IO],
    backoff: Int => Long = Webhooks.backoffSeconds,
    requestTimeout: FiniteDuration = 10.seconds
):

  /** Queue one delivery per active subscription listening for the event. */
  def enqueue(ws: UUID, event: String, data: Json): IO[Unit] =
    val payload = Webhooks.envelope(event, ws.toString, data).noSpaces
    sql"""insert into public.webhook_deliveries (workspace_id, subscription_id, event, payload, next_attempt_at)
          select $ws, s.id, $event, ${payload}::jsonb, now()
          from public.webhook_subscriptions s
          where s.workspace_id = $ws and s.active and ($event = any(s.events))""".update.run.void
      .transact(xa)

  /** Attempt every due delivery. Returns how many were attempted. */
  def processDue: IO[Int] =
    val due: IO[List[(UUID, UUID, String, String, String, String)]] =
      sql"""select d.id, d.workspace_id, d.event, d.payload::text, s.url, s.secret
            from public.webhook_deliveries d
            join public.webhook_subscriptions s on s.id = d.subscription_id
            where d.status = 'pending' and s.active
              and (d.next_attempt_at is null or d.next_attempt_at <= now())
            order by d.created_at
            limit 25"""
        .query[(UUID, UUID, String, String, String, String)]
        .to[List]
        .transact(xa)
    due.flatMap(_.traverse(attemptOne.tupled)).map(_.size)

  private def attemptOne(
      deliveryId: UUID,
      ws: UUID,
      event: String,
      payloadText: String,
      url: String,
      secret: String
  ): IO[Unit] =
    val timestamp = System.currentTimeMillis() / 1000L
    val signature = Webhooks.signatureHeader(secret, timestamp, payloadText)
    val req = Request[IO](Method.POST, Uri.unsafeFromString(url))
      .withHeaders(
        Headers(
          Header.Raw(CIString("Content-Type"), "application/json"),
          Header.Raw(CIString("X-Arcadence-Event"), event),
          Header.Raw(CIString("X-Arcadence-Delivery"), deliveryId.toString),
          Header.Raw(CIString("X-Arcadence-Signature"), signature),
          Header.Raw(CIString("X-Arcadence-Timestamp"), timestamp.toString)
        )
      )
      .withEntity(payloadText)
      .withContentType(`Content-Type`(MediaType.application.json))

    httpClient
      .run(req)
      .use(res => res.status.code.pure[IO])
      .timeoutTo(requestTimeout, IO.pure(-1))
      .attempt
      .map {
        case Right(code) if code >= 200 && code < 300 =>
          markDelivered(deliveryId, code)
        case Right(code) =>
          markFailed(deliveryId, code, s"endpoint returned HTTP $code")
        case Left(err) => markFailed(deliveryId, -1, err.getMessage.take(300))
      }
      .flatten

  private def markDelivered(deliveryId: UUID, code: Int): IO[Unit] =
    sql"""update public.webhook_deliveries set status = 'delivered', attempts = attempts + 1,
          response_code = $code, delivered_at = now(), next_attempt_at = null
          where id = $deliveryId""".update.run.void.transact(xa)

  private def markFailed(deliveryId: UUID, code: Int, err: String): IO[Unit] =
    val attempts: ConnectionIO[Int] =
      sql"""select attempts + 1 from public.webhook_deliveries where id = $deliveryId"""
        .query[Int]
        .unique
    attempts.transact(xa).flatMap { next =>
      if next >= Webhooks.maxAttempts then
        sql"""update public.webhook_deliveries set status = 'dead', attempts = $next,
              response_code = $code, last_error = $err, next_attempt_at = null
              where id = $deliveryId""".update.run.void.transact(xa)
      else
        val delay = backoff(next)
        sql"""update public.webhook_deliveries set status = 'pending', attempts = $next,
              response_code = $code, last_error = $err,
              next_attempt_at = now() + ($delay * interval '1 second')
              where id = $deliveryId""".update.run.void.transact(xa)
    }

  /** Replay a dead or failed delivery: resets it for a fresh attempt. */
  def replay(deliveryId: UUID, ws: UUID): IO[Boolean] =
    sql"""update public.webhook_deliveries set status = 'pending', attempts = 0,
          response_code = null, last_error = null, next_attempt_at = now()
          where id = $deliveryId and workspace_id = $ws and status in ('dead','failed')
          returning id""".query[UUID].option.transact(xa).map(_.isDefined)

  /** Background loop: process due deliveries periodically. */
  def loop(every: FiniteDuration = 30.seconds): fs2.Stream[IO, Int] =
    fs2.Stream
      .fixedDelay[IO](every)
      .evalMap(_ => processDue.attempt.map(_.getOrElse(0)))
