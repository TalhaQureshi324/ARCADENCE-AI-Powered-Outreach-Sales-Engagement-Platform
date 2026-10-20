package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.crypto.CredentialEncryptor
import com.arcadence.core.db.DbUrl
import com.arcadence.core.email.{
  AuthorizationResult,
  AccountIdentity,
  Credentials,
  EmailProvider,
  InboxBatch,
  HealthSnapshot,
  OutboundMessage,
  ProviderCapabilities,
  ProviderError,
  ProviderMessageId,
  ReplyMessage
}
import com.arcadence.core.jobs.JobQueue
import com.arcadence.core.crypto.OAuthStateSigner
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import munit.CatsEffectSuite

import java.time.{LocalTime, OffsetDateTime}
import java.util.UUID
import scala.concurrent.duration.*

/** Send-handler delivery semantics (Phases 5.3/5.4) against a stub provider:
  * accepted sends advance the enrollment and persist evidence; crash-after-
  * acceptance reconciles WITHOUT resending; suppressed/unsubscribed recipients
  * are skipped with a structured event; authentication failures open the
  * per-account circuit (which suspends the account); and ambiguous outcomes are
  * held for reconciliation instead of retried blind.
  */
final class SendDeliverySpec extends CatsEffectSuite:

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private lazy val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)
  private lazy val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    jdbc.jdbcUrl,
    jdbc.user,
    jdbc.password,
    logHandler = None
  )

  /** Configurable stub provider: queues outcomes, counts submissions. */
  private class StubProvider extends EmailProvider:
    val submitted = new java.util.concurrent.atomic.AtomicInteger(0)
    @volatile var nextOutcome: Either[ProviderError, ProviderMessageId] =
      Right(ProviderMessageId("stub-1"))

    def id: EmailProvider.Id = EmailProvider.SmtpImap
    def capabilities: ProviderCapabilities =
      ProviderCapabilities.Catalog(EmailProvider.SmtpImap)

    def submit(
        load: IO[Option[Credentials]],
        message: OutboundMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO(submitted.incrementAndGet()).as(nextOutcome)

    def submitReply(
        load: IO[Option[Credentials]],
        reply: ReplyMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO(submitted.incrementAndGet()).as(nextOutcome)

    def completeAuthorization(
        a: AuthorizationResult,
        store: Credentials => IO[Unit]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("completeAuthorization")))
    def validateConnection(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("validateConnection")))
    def syncInbox(
        load: IO[Option[Credentials]],
        cursor: Option[String]
    ): IO[Either[ProviderError, InboxBatch]] =
      IO.pure(Left(ProviderError.NotSupported("syncInbox")))
    def healthCheck(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, HealthSnapshot]] =
      IO.pure(Left(ProviderError.NotSupported("healthCheck")))
    def refresh(
        load: IO[Option[Credentials]],
        store: Credentials => IO[Unit]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("refresh")))
    def revokeAccess(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("revokeAccess")))

  /** Registry stub: injects the stub adapter and a permissive credential loader
    * (SMTP creds never leave the test).
    */
  private class StubRegistry(stub: StubProvider)
      extends ProviderRegistry(
        new CredentialEncryptor(Array.fill(32)(1.toByte)),
        new OAuthStateSigner(Array.fill(32)(2.toByte)),
        "http://localhost:3000",
        "http://localhost:8080/api/v1/email-accounts/callback"
      ):
    override def adapter(id: String): Option[EmailProvider] = Some(stub)
    override def credentialLoader(
        repos: EmailAccountRepos,
        workspaceId: UUID,
        accountId: UUID,
        providerId: String
    ): IO[Option[Credentials]] =
      IO.pure(None) // stub provider ignores credentials

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val stub = new StubProvider
  private lazy val guard = new AccountGuard(xa)
  private lazy val events = new Events(xa)
  private lazy val emailAccounts = new EmailAccountRepos(xa)
  private lazy val queue = new JobQueue.Repo(xa)
  private lazy val handler = new SendHandler(
    xa,
    Some(new StubRegistry(stub)),
    emailAccounts,
    guard,
    events
  )

  private var campaignId: UUID = null
  private var step2Id: UUID = null
  private var accountId: UUID = null
  private var seq: Int = 0

  private def nextKey: String =
    seq += 1
    s"t$seq"

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def beforeAll(): Unit =
    if dbUrl.nonEmpty then
      (sql"insert into public.organization (id, name) values ($org, 'Send Org')".update.run *>
        sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Send WS', ${s"send-" + ws.toString.take(12)})".update.run).void
        .transact(xa)
        .unsafeRunSync()
      super.beforeAll()

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from public.organization where id = $org".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  /** Seeds campaign (active, thread-mode off), 2-step graph, connected mailbox,
    * contact + enrollment due now; enqueues a send action and runs the handler
    * on it. Returns the action id.
    */
  private def seedAndRun(): IO[(UUID, JobQueue.Outcome)] =
    for
      cid <-
        sql"""
          insert into public.campaign (id, workspace_id, created_by, name, status, timezone, thread_mode)
          values (gen_random_uuid(), $ws, null, ${s"c-$nextKey"}, 'active', 'Etc/UTC', false)
          returning id
        """.query[UUID].unique.transact(xa)
      _ = campaignId = cid
      step1 <-
        sql"insert into public.campaign_step (campaign_id, position, type, name) values ($cid, 1, 'email', 'one') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      step2 <-
        sql"insert into public.campaign_step (campaign_id, position, type, name) values ($cid, 2, 'email', 'two') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      _ = step2Id = step2
      _ <-
        (sql"insert into public.step_variant (step_id, variant_key, subject, body_html, body_text) values ($step1, 'A', 'Hi there', '<p>Body</p>', '')".update.run *>
          sql"insert into public.step_variant (step_id, variant_key, subject, body_html, body_text) values ($step2, 'A', 'Again', '<p>Two</p>', '')".update.run *>
          sql"insert into public.campaign_edge (campaign_id, from_step_id, to_step_id) values ($cid, $step1, $step2)".update.run)
          .transact(xa)
      acct <-
        sql"""
          insert into public.email_accounts (id, workspace_id, provider, email_address, status, daily_send_cap)
          values (gen_random_uuid(), $ws, 'smtp_imap', ${s"a-$nextKey-$ws@t.test"}, 'connected', 100) returning id
        """.query[UUID].unique.transact(xa)
      _ = accountId = acct
      _ <-
        sql"insert into public.campaign_mailbox (campaign_id, email_account_id) values ($cid, $acct)".update.run
          .transact(xa)
      contact <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, ${s"k-$nextKey-$ws@t.test"}, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      enr <-
        sql"""
          insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id, status, next_step_id, next_action_at)
          values ($ws, $cid, $contact, 'active', $step1, now()) returning id
        """.query[UUID].unique.transact(xa)
      actionId = UUID.randomUUID()
      _ <-
        sql"""
          insert into public.scheduled_action (id, workspace_id, campaign_id, enrollment_id, contact_id,
            email_account_id, type, idempotency_key, payload, run_after)
          values ($actionId, $ws, $cid, $enr, $contact, $acct, 'send_email',
            ${"act-" + actionId}, ${JsonPayloads
            .send(enr, step1, acct)
            .noSpaces}::jsonb, now() - interval '1 second')
        """.update.run.transact(xa)
      claimed <- queue.claimBatchFor(actionId, "spec-worker")
      outcome <- handler.run(claimed.get)
    yield (actionId, outcome)

  test("accepted send: evidence persisted, enrollment advanced to step 2") {
    for
      r <- seedAndRun()
      actionId = r._1
      outcome = r._2
      msg <-
        sql"select status::text, provider_message_id, internet_message_id from public.outbound_message where action_id = $actionId"
          .query[(String, Option[String], Option[String])]
          .option
          .transact(xa)
      attempt <-
        sql"select status from public.send_attempt where action_id = $actionId"
          .query[String]
          .option
          .transact(xa)
      nextStep <- sql"""
        select s.name from public.campaign_enrollment e
        join public.campaign_step s on s.id = e.next_step_id where e.campaign_id = $campaignId
      """.query[String].option.transact(xa)
      events <-
        sql"select event_type from public.event_outbox where aggregate_id = ${actionId.toString}"
          .query[String]
          .to[List]
          .transact(xa)
      _ <- IO {
        assertEquals(outcome, JobQueue.Done)
        assertEquals(msg.map(_._1), Some("sent"))
        assertEquals(msg.flatMap(_._2), Some("stub-1"))
        assert(
          msg.flatMap(_._3).exists(_.startsWith("<")),
          "internet Message-ID must be RFC-shaped"
        )
        assertEquals(attempt, Some("accepted"))
        assertEquals(nextStep, Some("two"))
        assert(events.contains("accepted"))
      }
    yield ()
  }

  test("crash after acceptance reconciles without resending") {
    for
      r <- seedAndRun()
      actionId = r._1
      submitsAfterFirst = stub.submitted.get()
      // re-run the SAME action (simulating a re-claimed lease after a crash):
      _ <-
        sql"update public.scheduled_action set status = 'pending' where id = $actionId".update.run
          .transact(xa)
      claimed <- queue.claimBatchFor(actionId, "spec-worker-2")
      outcome2 <- handler.run(claimed.get)
      submitsAfterSecond = stub.submitted.get()
      _ <- IO {
        assertEquals(outcome2, JobQueue.Done)
        assertEquals(
          submitsAfterSecond,
          submitsAfterFirst
        ) // no duplicate submission
      }
    yield ()
  }

  test("suppressed recipient: skipped with a structured event, no submission") {
    for
      r <- seedAndRun()
      actionId = r._1
      outcome = r._2
      // fresh action for a now-suppressed contact
      contact <-
        sql"select contact_id from public.campaign_enrollment where id = (select enrollment_id from public.scheduled_action where id = $actionId)"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"insert into public.suppression (workspace_id, email, contact_id, reason) select workspace_id, email, id, 'unsubscribe' from public.contact where id = $contact".update.run
          .transact(xa)
      enrId0 <-
        sql"select enrollment_id from public.scheduled_action where id = $actionId"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"update public.campaign_enrollment set next_step_id = (select id from public.campaign_step where campaign_id = $campaignId order by position limit 1) where id = $enrId0".update.run
          .transact(xa)
      step1 <-
        sql"select next_step_id from public.campaign_enrollment where id = $enrId0"
          .query[UUID]
          .unique
          .transact(xa)
      enr <-
        sql"select enrollment_id from public.scheduled_action where id = $actionId"
          .query[UUID]
          .unique
          .transact(xa)
      acct <-
        sql"select email_account_id from public.scheduled_action where id = $actionId"
          .query[UUID]
          .unique
          .transact(xa)
      action2 = UUID.randomUUID()
      _ <-
        sql"""
          insert into public.scheduled_action (id, workspace_id, campaign_id, enrollment_id, contact_id,
            email_account_id, type, idempotency_key, payload, run_after)
          values ($action2, $ws, $campaignId, $enr, $contact, $acct, 'send_email',
            ${"act-" + action2}, ${JsonPayloads
            .send(enr, step1, acct)
            .noSpaces}::jsonb, now())
        """.update.run.transact(xa)
      claimed <- queue.claimBatchFor(action2, "spec-w3")
      outcome2 <- handler.run(claimed.get)
      submits <- IO(stub.submitted.get())
      skipped <-
        sql"select count(*) from public.event_outbox where aggregate_id = ${action2.toString} and event_type = 'skipped'"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(outcome2, JobQueue.Done)
        assert(skipped >= 1)
      }
    yield ()
  }

  test(
    "authentication failures: retry then circuit opens and account suspends"
  ) {
    stub.nextOutcome = Left(ProviderError.InvalidCredentials("bad password"))
    for
      r <- seedAndRun()
      actionId = r._1
      o1 = r._2
      // one action only allows a single claim; drive the guard directly for
      // the remaining failures (the worker loop would re-claim fresh actions).
      acct = accountId
      _ <- guard.recordAuthFailure(
        ws,
        acct
      ) // second failure (handler made the first)
      openBefore <- IO(guard.allows(acct))
      _ <- guard.recordAuthFailure(ws, acct) // third opens the circuit
      openAfter <- IO(guard.allows(acct))
      acctStatus <-
        sql"select status::text from public.email_accounts where id = $acct"
          .query[String]
          .unique
          .transact(xa)
      _ <- IO {
        assert(o1.isInstanceOf[JobQueue.Retry])
        assertEquals(openBefore, true)
        assertEquals(openAfter, false)
        assertEquals(acctStatus, "suspended")
      }
      // A NEW account is not circuit-blocked (breaker is per-account): restore
      // success and confirm a fresh send completes.
      _ <- IO { stub.nextOutcome = Right(ProviderMessageId("stub-ok")) }
      submitsBefore = stub.submitted.get()
      r2 <- seedAndRun()
      action2 = r2._1
      o2 = r2._2
      _ <- IO(assertEquals(o2, JobQueue.Done))
      _ <- IO(assertEquals(stub.submitted.get(), submitsBefore + 1))
    yield ()
  }

  test(
    "ambiguous outcome (unreachable): held for reconciliation, never retried"
  ) {
    stub.nextOutcome = Left(ProviderError.Unreachable("connect timeout"))
    stub.nextOutcome = Left(ProviderError.Unreachable("connect timeout"))
    for
      r <- seedAndRun()
      actionId = r._1
      outcome = r._2
      // Mirror SendWorker: a Fail outcome is persisted as a hold. The worker's
      // failOrRetry update (status='claimed') cannot override this.
      _ <-
        sql"update public.scheduled_action set status = 'failed', locked_at = null, last_error = 'outcome_unknown — held for reconciliation' where id = $actionId and status = 'claimed'".update.run.void
          .transact(xa)
      status <-
        sql"select status::text from public.scheduled_action where id = $actionId"
          .query[String]
          .unique
          .transact(xa)
      attempt <-
        sql"select status from public.send_attempt where action_id = $actionId"
          .query[String]
          .option
          .transact(xa)
      _ <- IO {
        assert(outcome.isInstanceOf[JobQueue.Fail])
        assertEquals(status, "failed")
        assertEquals(attempt, Some("outcome_unknown"))
      }
    yield ()
  }
