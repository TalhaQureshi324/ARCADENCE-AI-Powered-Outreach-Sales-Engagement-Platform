package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.crypto.{CredentialEncryptor, OAuthStateSigner}
import com.arcadence.core.db.DbUrl
import com.arcadence.core.email.{
  AccountIdentity,
  AuthorizationResult,
  Credentials,
  EmailProvider,
  InboxBatch,
  InboxMessage,
  HealthSnapshot,
  OutboundMessage,
  ProviderCapabilities,
  ProviderError,
  ProviderMessageId,
  ReplyMessage
}
import com.arcadence.core.jobs.JobQueue
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.time.{LocalDateTime, OffsetDateTime, ZoneId}
import java.util.UUID

/** Inbox sync + reply intelligence (Phases 6.1/6.2): idempotent inbound sync
  * (duplicates/out-of-order/delayed harmless), conservative threading (provider
  * thread id -> In-Reply-To -> contact fallback), deterministic classification,
  * reply effects (enrollment replied + pending follow-ups cancelled,
  * unsubscribe suppresses, bounce suppresses), and re-sync without duplicate
  * conversations.
  */
final class InboxSyncSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "second")

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

  private class StubProvider extends EmailProvider:
    @volatile var batch: InboxBatch = InboxBatch(Nil, None)
    val syncCount = new java.util.concurrent.atomic.AtomicInteger(0)
    def id: EmailProvider.Id = EmailProvider.SmtpImap
    def capabilities: ProviderCapabilities =
      ProviderCapabilities.Catalog(EmailProvider.SmtpImap)
    def syncInbox(
        load: IO[Option[Credentials]],
        cursor: Option[String]
    ): IO[Either[ProviderError, InboxBatch]] =
      IO(syncCount.incrementAndGet()).as(Right(batch))
    def submit(
        load: IO[Option[Credentials]],
        m: OutboundMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO.pure(Right(ProviderMessageId("stub")))
    def submitReply(
        load: IO[Option[Credentials]],
        r: ReplyMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO.pure(Right(ProviderMessageId("stub-reply")))
    def completeAuthorization(
        a: AuthorizationResult,
        s: Credentials => IO[Unit]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def validateConnection(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def healthCheck(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, HealthSnapshot]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def refresh(
        load: IO[Option[Credentials]],
        s: Credentials => IO[Unit]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def revokeAccess(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("x")))

  private class StubRegistry(stub: StubProvider)
      extends ProviderRegistry(
        new CredentialEncryptor(Array.fill(32)(1.toByte)),
        new OAuthStateSigner(Array.fill(32)(2.toByte)),
        "http://localhost:3000",
        "http://localhost:8080/api/v1/email-accounts/callback"
      ):
    override def adapter(id: String): Option[EmailProvider] = Some(stub)
    override def credentialLoader(
        r: EmailAccountRepos,
        w: UUID,
        a: UUID,
        p: String
    ): IO[Option[Credentials]] = IO.pure(None)

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val stub = new StubProvider
  private lazy val emailAccounts = new EmailAccountRepos(xa)
  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val sync =
    new InboxSyncService(xa, Some(new StubRegistry(stub)), emailAccounts)

  private var accountId: UUID = null
  private var campaignId: UUID = null
  private var enrollmentId: UUID = null
  private var contactEmail: String = "reply-me@t.test"
  private var seq = 0

  private def nextKey: String =
    seq += 1
    s"s$seq"

  private def msg(
      providerId: String,
      from: String,
      subject: String,
      body: String,
      threadId: Option[String] = None,
      internetId: Option[String] = None,
      received: OffsetDateTime = OffsetDateTime.now()
  ): InboxMessage =
    InboxMessage(
      providerId,
      threadId,
      internetId,
      from,
      Some(subject),
      received,
      textBody = Some(body),
      htmlBody = None
    )

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, workspace, campaign + enrollment for threading") {
    for
      _ <- sql"delete from public.workspace where name = 'Inbox WS'".update.run
        .transact(xa)
      _ <-
        (sql"insert into public.organization (id, name) values ($org, 'Inbox Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Inbox WS', ${"inbox-" + ws.toString.take(12)})".update.run).void
          .transact(xa)
      acct <-
        sql"""
          insert into public.email_accounts (id, workspace_id, provider, email_address, status)
          values (gen_random_uuid(), $ws, 'smtp_imap', ${s"box-$ws@t.test"}, 'connected') returning id
        """.query[UUID].unique.transact(xa)
      _ = accountId = acct
      cid <-
        sql"""
          insert into public.campaign (id, workspace_id, created_by, name, status)
          values (gen_random_uuid(), $ws, null, 'Inbox campaign', 'active') returning id
        """.query[UUID].unique.transact(xa)
      _ = campaignId = cid
      step <-
        sql"insert into public.campaign_step (campaign_id, position, type, name) values ($cid, 1, 'email', 'one') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"insert into public.step_variant (step_id, variant_key, subject, body_html, body_text) values ($step, 'A', 'S', '<p>b</p>', 'b')".update.run
          .transact(xa)
      contact <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, $contactEmail, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      enr <-
        sql"""
          insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id, status, next_step_id, next_action_at)
          values ($ws, $cid, $contact, 'active', $step, now()) returning id
        """.query[UUID].unique.transact(xa)
      _ = enrollmentId = enr
      // two pending follow-ups that a reply must cancel
      _ <-
        (1 to 2).toList.traverse { i =>
          sql"""
            insert into public.scheduled_action (id, workspace_id, campaign_id, enrollment_id, contact_id,
              email_account_id, type, idempotency_key, payload, run_after)
            values (gen_random_uuid(), $ws, $cid, $enr, $contact, $acct, 'send_email',
              ${s"pend-$enr-$i"}, '{}', now() + interval '2 days')
          """.update.run.transact(xa)
        }
      // one outbound message already sent in a thread (for In-Reply-To linkage)
      _ <-
        sql"""
          insert into public.outbound_message (workspace_id, campaign_id, enrollment_id, contact_id,
            email_account_id, subject, body_text, internet_message_id, status)
          values ($ws, $cid, $enr, $contact, $acct, 'Our intro', 'intro', ${"<intro-" + ws.toString + "@t.test>"}, 'sent')
        """.update.run.transact(xa)
    yield ()
  }

  test(
    "sync: stores inbound messages, threads by provider id, idempotent on re-run"
  ) {
    val threadId = s"thr-${UUID.randomUUID()}"
    val batch = List(
      msg(
        "pm-1",
        contactEmail,
        "Re: Our intro",
        "Sounds interesting",
        threadId = Some(threadId)
      ),
      msg(
        "pm-2",
        contactEmail,
        "Re: Our intro",
        "What about pricing?",
        threadId = Some(threadId)
      )
    )
    stub.batch = InboxBatch(batch, Some("cursor-1"))
    for
      n1 <- sync.syncAccount(ws, accountId)
      threads <-
        sql"select count(*) from public.email_threads where workspace_id = $ws"
          .query[Long]
          .unique
          .transact(xa)
      messages <-
        sql"select count(*) from public.email_messages where workspace_id = $ws"
          .query[Long]
          .unique
          .transact(xa)
      n2 <- sync.syncAccount(ws, accountId) // duplicate delivery
      threads2 <-
        sql"select count(*) from public.email_threads where workspace_id = $ws"
          .query[Long]
          .unique
          .transact(xa)
      messages2 <-
        sql"select count(*) from public.email_messages where workspace_id = $ws"
          .query[Long]
          .unique
          .transact(xa)
      events <-
        sql"select count(*) from public.inbound_events where email_account_id = $accountId"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(n1, 2)
        assertEquals(threads, 1L) // both attach by provider thread id
        assertEquals(messages, 2L)
        assertEquals(n2, 0) // refs dedupe
        assertEquals(threads2, 1L)
        assertEquals(messages2, 2L)
        assertEquals(events, 2L)
      }
    yield ()
  }

  test(
    "classification + effects: reply marks enrollment replied and cancels pending follow-ups"
  ) {
    val m = msg(
      "pm-4",
      contactEmail,
      "Re: Our intro",
      "I'm interested, is there a meeting slot?"
    )
    stub.batch = InboxBatch(List(m), None)
    for
      _ <- sync.syncAccount(ws, accountId)
      cls <-
        sql"select classification from public.email_threads where workspace_id = $ws order by last_message_at desc limit 1"
          .query[String]
          .unique
          .transact(xa)
      enrStatus <-
        sql"select status::text from public.campaign_enrollment where id = $enrollmentId"
          .query[String]
          .unique
          .transact(xa)
      pending <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enrollmentId and status = 'pending'"
          .query[Long]
          .unique
          .transact(xa)
      cancelled <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enrollmentId and status = 'cancelled'"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assert(cls == "interested" || cls == "meeting_requested", cls)
        assertEquals(enrStatus, "replied")
        assertEquals(pending, 0L)
        assertEquals(cancelled, 2L)
      }
    yield ()
  }

  test("unsubscribe suppresses and unsubscribes; bounce suppresses + bounces") {
    for
      // a second contact/thread: unsubscribe
      c2 <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, ${"unsub-" + nextKey + ws.toString.take(6) + "@t.test"}, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      acct2 = accountId
      t2 <-
        sql"insert into public.email_threads (workspace_id, email_account_id, contact_id, subject) values ($ws, $acct2, $c2, 'no') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      c2Email <- sql"select email from public.contact where id = $c2"
        .query[String]
        .unique
        .transact(xa)
      mUnsub = msg("pm-u1", c2Email, "RE: remove me", "Please unsubscribe me")
      _ <- sync.process(ws, acct2, mUnsub)
      clsUnsub <-
        sql"select classification from public.email_threads where id = $t2"
          .query[String]
          .unique
          .transact(xa)
      c2State <- sql"select state::text from public.contact where id = $c2"
        .query[String]
        .unique
        .transact(xa)
      c2Sup <-
        sql"select count(*) from public.suppression where contact_id = $c2"
          .query[Long]
          .unique
          .transact(xa)
      // third contact: hard bounce via mailer-daemon
      c3 <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, ${"bounce-" + nextKey + ws.toString.take(6) + "@t.test"}, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      t3 <-
        sql"insert into public.email_threads (workspace_id, email_account_id, contact_id, subject) values ($ws, $acct2, $c3, 'returned mail') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      c3Email <- sql"select email from public.contact where id = $c3"
        .query[String]
        .unique
        .transact(xa)
      mBounce = msg(
        "pm-b1",
        c3Email,
        "Undeliverable: Our intro",
        "550 permanent error"
      )
      _ <- sync.process(ws, acct2, mBounce)
      c3State <- sql"select state::text from public.contact where id = $c3"
        .query[String]
        .unique
        .transact(xa)
      c3Sup <-
        sql"select count(*) from public.suppression where contact_id = $c3"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(clsUnsub, "unsubscribe")
        assertEquals(c2State, "unsubscribed")
        assertEquals(c2Sup, 1L)
        assertEquals(c3State, "bounced")
        assertEquals(c3Sup, 1L)
      }
    yield ()
  }
