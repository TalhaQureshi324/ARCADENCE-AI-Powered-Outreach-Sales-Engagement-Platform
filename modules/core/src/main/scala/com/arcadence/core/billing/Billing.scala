package com.arcadence.core.billing

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json
import io.circe.parser.parse

import java.util.UUID

/** Billing domain (Phase 13.1). Stripe webhook events are processed
  * idempotently: the event id is the dedupe key in billing_events, so
  * duplicate, delayed, or out-of-order delivery can never double-apply. Payment
  * failure downgrades entitlements to free but NEVER deletes customer data.
  * Plan catalogs and limits live here — independent of any frontend labels.
  */
object Billing:

  final case class PlanEntitlements(
      plan: String,
      maxMailboxes: Int,
      maxUsers: Int,
      maxActiveLeads: Long,
      monthlySendCap: Long,
      monthlyAiTokens: Long,
      monthlyEnrichmentCredits: Int,
      agencyWorkspacesAllowed: Boolean,
      apiRequestsPerMinute: Int
  )

  /** Plan catalog: single source of truth for entitlements. */
  val plans: Map[String, PlanEntitlements] = Map(
    "free" -> PlanEntitlements(
      "free",
      1,
      2,
      1000L,
      1000L,
      200000L,
      50,
      false,
      60
    ),
    "starter" -> PlanEntitlements(
      "starter",
      3,
      5,
      10000L,
      25000L,
      1000000L,
      500,
      false,
      120
    ),
    "growth" -> PlanEntitlements(
      "growth",
      10,
      15,
      100000L,
      250000L,
      5000000L,
      5000,
      false,
      300
    ),
    "agency" -> PlanEntitlements(
      "agency",
      50,
      50,
      1000000L,
      1000000L,
      20000000L,
      25000,
      true,
      600
    )
  )

  def entitlementsFor(plan: String): PlanEntitlements =
    plans.getOrElse(plan, plans("free"))

  /** Record an event exactly once. Returns true if this is the first delivery
    * (proceed with handling); false for duplicates (skip).
    */
  def recordEvent(
      workspaceId: Option[UUID],
      stripeEventId: String,
      eventType: String,
      payload: Json
  ): ConnectionIO[Boolean] =
    sql"""insert into public.billing_events (workspace_id, stripe_event_id, event_type, payload)
          values ($workspaceId, $stripeEventId, $eventType, ${payload.noSpaces}::jsonb)
          on conflict (stripe_event_id) do nothing returning id"""
      .query[UUID]
      .option
      .map(_.isDefined)

  /** Upsert the subscription row + entitlements from a Stripe subscription
    * snapshot. Idempotent by stripe_subscription_id.
    */
  def applySubscription(
      ws: UUID,
      customerId: String,
      subscriptionId: String,
      plan: String,
      status: String,
      periodEnd: Option[String],
      cancelAtPeriodEnd: Boolean
  ): ConnectionIO[Unit] =
    val ent = entitlementsFor(plan)
    val periodText =
      periodEnd.map(pe => parse(pe).fold(_ => pe, j => j.noSpaces))
    for
      _ <-
        sql"""insert into public.billing_customers (workspace_id, stripe_customer_id)
              values ($ws, $customerId)
              on conflict (workspace_id) do update set stripe_customer_id = excluded.stripe_customer_id""".update.run.void
      _ <-
        sql"""insert into public.subscriptions
              (workspace_id, stripe_customer_id, stripe_subscription_id, plan, status, current_period_end, cancel_at_period_end)
              values ($ws, $customerId, $subscriptionId, $plan, $status, ${periodText}::timestamptz, $cancelAtPeriodEnd)
              on conflict (stripe_subscription_id) do update
                set plan = excluded.plan, status = excluded.status,
                    current_period_end = excluded.current_period_end,
                    cancel_at_period_end = excluded.cancel_at_period_end,
                    updated_at = now()""".update.run.void
      _ <- upsertEntitlements(ws, ent)
    yield ()

  def upsertEntitlements(ws: UUID, ent: PlanEntitlements): ConnectionIO[Unit] =
    sql"""insert into public.entitlements
          (workspace_id, plan, max_mailboxes, max_users, max_active_leads, monthly_send_cap,
           monthly_ai_tokens, monthly_enrichment_credits, agency_workspaces_allowed, api_requests_per_minute)
          values ($ws, ${ent.plan}, ${ent.maxMailboxes}, ${ent.maxUsers}, ${ent.maxActiveLeads},
                  ${ent.monthlySendCap}, ${ent.monthlyAiTokens}, ${ent.monthlyEnrichmentCredits},
                  ${ent.agencyWorkspacesAllowed}, ${ent.apiRequestsPerMinute})
          on conflict (workspace_id) do update
            set plan = excluded.plan, max_mailboxes = excluded.max_mailboxes,
                max_users = excluded.max_users, max_active_leads = excluded.max_active_leads,
                monthly_send_cap = excluded.monthly_send_cap, monthly_ai_tokens = excluded.monthly_ai_tokens,
                monthly_enrichment_credits = excluded.monthly_enrichment_credits,
                agency_workspaces_allowed = excluded.agency_workspaces_allowed,
                api_requests_per_minute = excluded.api_requests_per_minute,
                updated_at = now()""".update.run.void

  /** Mark delinquent: downgrade entitlements to free, keep data and
    * subscription history intact.
    */
  def markDelinquent(ws: UUID): ConnectionIO[Unit] =
    upsertEntitlements(ws, plans("free"))

  def entitlementsForWorkspace(
      ws: UUID
  ): ConnectionIO[Option[PlanEntitlements]] =
    sql"""select plan, max_mailboxes, max_users, max_active_leads, monthly_send_cap,
          monthly_ai_tokens, monthly_enrichment_credits, agency_workspaces_allowed, api_requests_per_minute
          from public.entitlements where workspace_id = $ws"""
      .query[(String, Int, Int, Long, Long, Long, Int, Boolean, Int)]
      .option
      .map(
        _.map(t =>
          PlanEntitlements(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9)
        )
      )

  /** Maps a Stripe price id to a plan via env-configured catalog. Absent
    * mapping -> free (fail-safe).
    */
  def planForPrice(priceId: String, priceMap: Map[String, String]): String =
    priceMap.getOrElse(priceId, "free")
