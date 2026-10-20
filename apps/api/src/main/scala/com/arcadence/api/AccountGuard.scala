package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json

import java.util.UUID

/** Per-account protective state (Phase 5.4): three consecutive authentication
  * failures open a circuit for the account and suspend it (with a health
  * event), stopping further sends until an operator reconnects. The in-memory
  * cooldown is worker-local; the suspension is persisted so every worker sees
  * it.
  */
final class AccountGuard(xa: Transactor[IO]):
  private val failures = scala.collection.mutable.Map.empty[UUID, Int]

  def allows(accountId: UUID): Boolean =
    failures.get(accountId).forall(_ < AccountGuard.AuthFailureThreshold)

  def recordAuthFailure(workspaceId: UUID, accountId: UUID): IO[Unit] =
    val n = failures.getOrElse(accountId, 0) + 1
    failures(accountId) = n
    if n >= AccountGuard.AuthFailureThreshold then
      (for
        _ <-
          sql"""
            update public.email_accounts
            set status = 'suspended', status_message = 'Circuit breaker: repeated authentication failures'
            where id = $accountId and status = 'connected'
          """.update.run
        _ <-
          sql"""
            insert into public.account_health_events (workspace_id, email_account_id, event_type, message, details)
            values ($workspaceId, $accountId, 'paused', 'Circuit breaker: repeated authentication failures',
                    '{"reason": "auth_failures"}'::jsonb)
          """.update.run
      yield ()).transact(xa)
    else IO.unit

object AccountGuard:
  val AuthFailureThreshold = 3

/** Structured operational events (Phase 5.4): every queued/deferred/skipped/
  * sending/accepted/failed/reconciled send operation lands in the outbox with a
  * human-readable reason, giving one auditable trail per decision.
  */
final class Events(xa: Transactor[IO]):
  def record(
      workspaceId: UUID,
      aggregateType: String,
      aggregateId: String,
      eventType: String,
      payload: Json
  ): IO[Unit] =
    sql"""
      insert into public.event_outbox (workspace_id, aggregate_type, aggregate_id, event_type, payload)
      values ($workspaceId, $aggregateType, $aggregateId, $eventType, ${payload.noSpaces}::jsonb)
    """.update.run.void.transact(xa)
