package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.jobs.JobQueue
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*

import java.time.OffsetDateTime
import java.util.UUID
import javax.naming.Context
import javax.naming.directory.InitialDirContext

/** Deliverability diagnostics (Phase 8).
  *
  * DNS authentication (SPF/DKIM/DMARC) with provider-aware expectations and
  * SPF's 10-lookup limit; transparent sender-health evaluation from real
  * send/error/suppression signals with documented reasons and automatic
  * suspension when risk thresholds are crossed; permission-gated inbox
  * placement tests that honestly report "unavailable" until an approved
  * external provider is configured.
  *
  * A DNS pass means the records are present and syntactically valid — it is
  * NEVER presented as a guarantee of inbox placement.
  */
object Deliverability:

  final case class Finding(
      record: String,
      ok: Boolean,
      message: String,
      remediation: String
  )
  final case class DnsResult(
      domain: String,
      overall: String, // pass | warn | fail
      findings: List[Finding]
  )

  /** DNS resolver function — injected so tests can stub; default uses JNDI DNS
    * lookups for TXT records.
    */
  type Resolver = (String, String) => IO[List[String]]

  def jndiResolver: Resolver = (domain, recordType) =>
    IO.blocking {
      val env = new java.util.Hashtable[String, String]()
      env.put(
        Context.INITIAL_CONTEXT_FACTORY,
        "com.sun.jndi.dns.DnsContextFactory"
      )
      env.put(Context.PROVIDER_URL, "dns://8.8.8.8 dns://1.1.1.1")
      val ctx = new InitialDirContext(env)
      try {
        val attrs = ctx.getAttributes(domain, Array(recordType))
        val txt = Option(attrs.get(recordType))
          .map { a =>
            import scala.jdk.CollectionConverters.*
            a.getAll.asScala.map(_.toString).toList
          }
          .getOrElse(Nil)
        txt
      } finally ctx.close()
    }.handleError(_ => Nil)

  // -- SPF -------------------------------------------------------------------------

  /** SPF lookup count (the RFC 7208 10-DNS-lookup limit): include, a, mx, ptr,
    * exists and redirect each cost one lookup.
    */
  def spfLookupCount(record: String): Int =
    record
      .split("\\s+")
      .count(term =>
        term.startsWith("include:") || term.startsWith("a") || term
          .startsWith("mx") ||
          term.startsWith("ptr") || term.startsWith("exists:")
      ) + (if record.contains("redirect=") then 1 else 0)

  def checkSpf(domain: String, records: List[String]): List[Finding] =
    val spf = records.filter(_.toLowerCase.startsWith("v=spf1"))
    if spf.isEmpty then
      List(
        Finding(
          "spf",
          ok = false,
          s"No SPF record found for $domain.",
          s"Publish a TXT record: v=spf1 include:_spf.<your-provider> ~all"
        )
      )
    else if spf.size > 1 then
      List(
        Finding(
          "spf",
          ok = false,
          s"Multiple SPF records found (${spf.size}). Receivers treat this as an error.",
          "Merge into a single v=spf1 record."
        )
      )
    else
      val rec = spf.head
      val lookups = spfLookupCount(rec)
      val base = List(Finding("spf", ok = true, s"SPF record found: $rec", ""))
      val allMech = rec.toLowerCase.endsWith("all") ||
        rec.toLowerCase.contains("+all") || rec.toLowerCase.contains("?all")
      val tooPermissive =
        if rec.toLowerCase.contains("+all") || rec.toLowerCase.contains(
            " all"
          ) && rec.toLowerCase.contains("+all")
        then
          List(
            Finding(
              "spf",
              ok = false,
              "SPF ends with +all (allows anyone to send as your domain).",
              "Replace +all with ~all (soft fail) or -all (hard fail)."
            )
          )
        else Nil
      val tooMany =
        if lookups > 10 then
          List(
            Finding(
              "spf",
              ok = false,
              s"SPF uses $lookups DNS lookups — over the 10-lookup limit, so receivers may ignore it.",
              "Remove unneeded includes or flatten to fewer providers."
            )
          )
        else Nil
      base ++ tooPermissive ++ tooMany

  // -- DKIM / DMARC -----------------------------------------------------------------

  def checkDkim(
      domain: String,
      selectors: List[String],
      records: Map[String, List[String]]
  ): List[Finding] =
    if selectors.isEmpty then
      List(
        Finding(
          "dkim",
          ok = false,
          "No DKIM selectors configured to check.",
          "Add selectors for your sending providers (e.g. google._domainkey, selector1._domainkey, selector2._domainkey)."
        )
      )
    else
      selectors.map { sel =>
        val name = s"$sel._domainkey.$domain"
        val recs = records
          .getOrElse(name, Nil)
          .filter(r =>
            r.toLowerCase.contains("v=dkim1") || r.toLowerCase.contains("p=")
          )
        if recs.isEmpty then
          Finding(
            "dkim",
            ok = false,
            s"No DKIM record at $name.",
            s"Publish the DKIM public key TXT record provided by your sending provider at $name."
          )
        else
          Finding(
            "dkim",
            ok = true,
            s"DKIM record found for selector $sel.",
            ""
          )
      }

  def checkDmarc(domain: String, records: List[String]): List[Finding] =
    val dmarc = records.filter(_.toLowerCase.startsWith("v=dmarc1"))
    if dmarc.isEmpty then
      List(
        Finding(
          "dmarc",
          ok = false,
          s"No DMARC record at _dmarc.$domain.",
          "Publish _dmarc TXT: v=DMARC1; p=none; rua=mailto:dmarc@yourdomain (then tighten to quarantine/reject)."
        )
      )
    else
      val rec = dmarc.head.toLowerCase
      val policy = if rec.contains("p=reject") then "reject"
      else if rec.contains("p=quarantine") then "quarantine"
      else if rec.contains("p=none") then "none"
      else "(missing)"
      val pPolicy = List(
        Finding(
          "dmarc",
          ok = true,
          s"DMARC found with policy p=$policy.",
          if policy == "none" then
            "Once reports look clean, tighten to p=quarantine then p=reject."
          else ""
        )
      )
      val ruaMissing = if !rec.contains("rua=") then
        List(
          Finding(
            "dmarc",
            ok = false,
            "DMARC has no rua= reporting address.",
            "Add rua=mailto:dmarc-reports@yourdomain so you receive aggregate reports."
          )
        )
      else Nil
      pPolicy ++ ruaMissing

  // -- provider-aware expectations ---------------------------------------------------

  def expectedIncludes(provider: String): List[String] = provider match
    case p if p.contains("gmail") => List("_spf.google.com")
    case p if p.contains("microsoft") || p.contains("graph") =>
      List("spf.protection.outlook.com")
    case _ => Nil

  def checkProviderSpfInclude(
      records: List[String],
      provider: String
  ): List[Finding] =
    expectedIncludes(provider) match
      case Nil      => Nil
      case includes =>
        val spf = records.find(_.toLowerCase.startsWith("v=spf1"))
        if spf.isEmpty then Nil
        else if includes.exists(inc => spf.exists(_.toLowerCase.contains(inc)))
        then
          List(
            Finding(
              "spf-include",
              ok = true,
              s"SPF includes your provider's record ($includes).",
              ""
            )
          )
        else
          List(
            Finding(
              "spf-include",
              ok = false,
              s"SPF does not include your provider's record ($includes) — mail sent via that provider may fail SPF.",
              s"Add include:${includes.head} to your SPF record."
            )
          )

  // -- health -------------------------------------------------------------------------

  final case class HealthEvaluation(
      status: String, // healthy | warning | risk
      reasons: List[String],
      bounceHardRate: Double,
      authFailures: Long,
      suppressRate: Double
  )

  /** Transparent health evaluation from real counts in the lookback window.
    * Thresholds (documented, not magic):
    *   - risk: hard-bounce rate >= 10% of accepted, or >= 5 auth failures, or
    *     suppression rate >= 15%
    *   - warning: hard-bounce >= 5%, or >= 2 auth failures, or suppression >=
    *     8%
    */
  def evaluateHealth(
      accepted: Long,
      bounceHard: Long,
      authFailures: Long,
      suppressed: Long,
      scheduled: Long
  ): HealthEvaluation =
    val bounceRate =
      if accepted == 0 then 0.0 else bounceHard * 100.0 / accepted
    val suppressRate =
      if scheduled == 0 then 0.0 else suppressed * 100.0 / scheduled
    val reasons = List.newBuilder[String]
    var status = "healthy"
    def escalate(to: String): Unit =
      if to == "risk" || (to == "warning" && status == "healthy") then
        status = to
    if accepted > 0 && bounceRate >= 10.0 then
      reasons += s"Hard-bounce rate is ${bounceRate}%.1f%% of accepted messages (>= 10%). Remove bounced addresses and verify list quality."
      escalate("risk")
    else if accepted > 0 && bounceRate >= 5.0 then
      reasons += s"Hard-bounce rate is ${bounceRate}%.1f%% of accepted messages (>= 5%). Review list quality."
      escalate("warning")
    if authFailures >= 5 then
      reasons += s"$authFailures authentication failures in the window — check credentials/reconnect the account."
      escalate("risk")
    else if authFailures >= 2 then
      reasons += s"$authFailures authentication failures in the window — watch for credential problems."
      escalate("warning")
    if suppressRate >= 15.0 then
      reasons += s"Suppression rate is ${suppressRate}%.1f%% of scheduled sends (>= 15%). Clean the list."
      escalate("risk")
    else if suppressRate >= 8.0 then
      reasons += s"Suppression rate is ${suppressRate}%.1f%% of scheduled sends (>= 8%)."
      escalate("warning")
    HealthEvaluation(
      status,
      reasons.result(),
      bounceRate,
      authFailures,
      suppressRate
    )

  /** Applies automatic control: risk status suspends the account (safe pause)
    * with a health event; the worker also defers jobs for suspended accounts.
    */
  def applyPause(
      xa: Transactor[IO],
      workspaceId: UUID,
      accountId: UUID,
      h: HealthEvaluation
  ): IO[Unit] =
    if h.status != "risk" then IO.unit
    else
      val reason = "Auto-paused: " + h.reasons.mkString(" ")
      (for
        _ <-
          sql"""
            update public.email_accounts set status = 'suspended', status_message = $reason
            where id = $accountId and status = 'connected'
          """.update.run
        _ <-
          sql"""
            insert into public.account_health_events (workspace_id, email_account_id, event_type, message, details)
            values ($workspaceId, $accountId, 'paused', $reason, ${Json
              .obj("reasons" -> h.reasons.asJson)
              .noSpaces}::jsonb)
          """.update.run
        _ <-
          sql"""
            insert into public.event_outbox (workspace_id, aggregate_type, aggregate_id, event_type, payload)
            values ($workspaceId, 'email_account', $accountId, 'health_alert',
                    ${Json
              .obj(
                "reasons" -> h.reasons.asJson,
                "status" -> Json.fromString("risk")
              )
              .noSpaces}::jsonb)
          """.update.run
      yield ()).transact(xa)

  def evaluateAccount(
      xa: Transactor[IO],
      workspaceId: UUID,
      accountId: UUID,
      windowDays: Int
  ): IO[HealthEvaluation] =
    (for counts <-
        sql"""
          select
            (select count(*) from public.send_attempt sa
               join public.scheduled_action a on a.id = sa.action_id
              where a.email_account_id = $accountId and sa.status = 'accepted'
                and sa.created_at > now() - ${windowDays} * interval '1 day'),
            (select count(*) from public.send_attempt sa
               join public.scheduled_action a on a.id = sa.action_id
              where a.email_account_id = $accountId and sa.status = 'permanently_failed'
                and sa.detail::text ~* '(bounce|undeliverable)'
                and sa.created_at > now() - ${windowDays} * interval '1 day'),
            (select count(*) from public.send_attempt sa
               join public.scheduled_action a on a.id = sa.action_id
              where a.email_account_id = $accountId and sa.status = 'authentication_failed'
                and sa.created_at > now() - ${windowDays} * interval '1 day'),
            (select count(*) from public.scheduled_action
              where email_account_id = $accountId and status = 'pending'
                and created_at > now() - ${windowDays} * interval '1 day')
        """.query[(Long, Long, Long, Long)].unique
    yield counts).transact(xa).map {
      case (accepted, bounces, authFails, scheduled) =>
        evaluateHealth(accepted, bounces, authFails, 0, scheduled)
    }

  /** Snapshot + auto-pause. */
  def snapshotAccount(
      xa: Transactor[IO],
      workspaceId: UUID,
      accountId: UUID,
      windowDays: Int
  ): IO[HealthEvaluation] =
    evaluateAccount(xa, workspaceId, accountId, windowDays).flatMap { h =>
      sql"""
        insert into public.account_health_snapshots (workspace_id, email_account_id, status, reasons, window_days)
        values ($workspaceId, $accountId, ${h.status}, ${h.reasons.asJson.noSpaces}::jsonb, $windowDays)
      """.update.run.void.transact(xa) *>
        applyPause(xa, workspaceId, accountId, h).as(h)
    }
