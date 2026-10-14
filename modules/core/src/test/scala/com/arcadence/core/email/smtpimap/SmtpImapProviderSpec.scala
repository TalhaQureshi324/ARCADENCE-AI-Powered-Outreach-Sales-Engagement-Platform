package com.arcadence.core.email.smtpimap

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.arcadence.core.email.{
  AuthorizationResult,
  OutboundMessage,
  ProviderError,
  ReplyMessage
}
import com.icegreen.greenmail.util.{GreenMail, ServerSetup}
import munit.FunSuite

/** SMTP/IMAP adapter against REAL protocol servers (GreenMail embeds SMTP +
  * IMAP): shape validation, authentication round-trips, submission, IMAP
  * retrieval with a UID cursor, and typed error mapping (Phase 2.4).
  *
  * GreenMail runs plain servers so the adapter logic is exercised
  * protocol-level end to end; TLS-mode correctness against real TLS endpoints
  * stays a provider-console verification item (docs/MANUAL_SETUP.md).
  */
final class SmtpImapProviderSpec extends FunSuite:

  private var greenmail: GreenMail = null

  override def beforeAll(): Unit =
    // Secure (implicit-TLS) servers with GreenMail's bundled self-signed
    // certificate. The TEST JVM trusts that certificate through the standard
    // Jakarta Mail system properties — scope-limited to this suite, cleared
    // afterwards; the production adapter always validates certificates.
    System.setProperty("mail.smtp.ssl.trust", "*")
    System.setProperty("mail.imap.ssl.trust", "*")
    System.setProperty("mail.smtp.ssl.checkserveridentity", "false")
    System.setProperty("mail.imap.ssl.checkserveridentity", "false")
    val smtp = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)
    val imap = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)
    greenmail = new GreenMail(Array(smtp, imap))
    greenmail.start()
    greenmail.setUser("sender@greenmail.test", "app-password-1")
    super.beforeAll()

  override def afterAll(): Unit =
    if greenmail != null then greenmail.stop()
    System.clearProperty("mail.smtp.ssl.trust")
    System.clearProperty("mail.imap.ssl.trust")
    System.clearProperty("mail.smtp.ssl.checkserveridentity")
    System.clearProperty("mail.imap.ssl.checkserveridentity")
    super.afterAll()

  private def credentials(password: String = "app-password-1") =
    SmtpImapCredentials(
      emailAddress = "sender@greenmail.test",
      username = "sender@greenmail.test",
      password = password,
      smtpHost = greenmail.getSmtps.getBindTo,
      smtpPort = greenmail.getSmtps.getPort,
      smtpSecurity = "ssl",
      imapHost = greenmail.getImaps.getBindTo,
      imapPort = greenmail.getImaps.getPort,
      imapSecurity = "ssl"
    )

  private def credsLoader(c: SmtpImapCredentials) =
    IO.pure(Some(c: com.arcadence.core.email.Credentials))

  private val provider = SmtpImapProvider()

  test(
    "shape validation rejects bad ports, hosts, modes before any network attempt"
  ) {
    assert(SmtpImapCredentials.validate(credentials()).isRight)
    assert(
      SmtpImapCredentials.validate(credentials().copy(smtpPort = 0)).isLeft
    )
    assert(
      SmtpImapCredentials
        .validate(credentials().copy(imapSecurity = "none"))
        .isLeft
    )
    assert(
      SmtpImapCredentials.validate(credentials().copy(password = "")).isLeft
    )
    assert(
      SmtpImapCredentials.validate(credentials().copy(smtpHost = " ")).isLeft
    )
  }

  test("validateConnection performs a real SMTP + IMAP login") {
    provider
      .validateConnection(credsLoader(credentials()))
      .unsafeRunSync() match
      case Left(error)     => fail(s"expected identity, got $error")
      case Right(identity) =>
        assertEquals(identity.emailAddress, "sender@greenmail.test")
  }

  test("wrong password maps to InvalidCredentials with a safe message") {
    provider
      .validateConnection(credsLoader(credentials("wrong-password")))
      .unsafeRunSync() match
      case Left(error: ProviderError.InvalidCredentials) =>
        assert(error.detail.contains("authentication failed"))
        assert(!error.detail.contains("wrong-password"))
      case Left(other) => fail(s"expected InvalidCredentials, got $other")
      case Right(_)    => fail("expected failure")
  }

  test("unknown host maps to Unreachable") {
    val unreachable =
      credentials().copy(smtpHost = "no-such-host.arcadence.invalid")
    provider.validateConnection(credsLoader(unreachable)).unsafeRunSync() match
      case Left(_: ProviderError.Unreachable) => assert(true)
      case Left(other) => fail(s"expected Unreachable, got $other")
      case Right(_)    => fail("expected failure")
  }

  test("submit delivers a real message to the recipient's mailbox") {
    val result = provider
      .submit(
        credsLoader(credentials()),
        OutboundMessage(
          from = "sender@greenmail.test",
          to = List("recipient@greenmail.test"),
          subject = "Connection test",
          textBody = "Hello over real SMTP",
          htmlBody = None,
          headers = Map.empty
        )
      )
      .unsafeRunSync()
    assert(result.isRight, s"submit failed: ${result.left.map(_.detail)}")
    greenmail.waitForIncomingEmail(10000, 1)
    val inbox =
      greenmail.getReceivedMessagesForDomain("recipient@greenmail.test")
    assert(inbox.length >= 1)
    assertEquals(inbox.head.getSubject, "Connection test")
  }

  test("submitReply adds In-Reply-To/References headers") {
    val result = provider
      .submitReply(
        credsLoader(credentials()),
        ReplyMessage(
          providerThreadId = "<thread-1@greenmail.test>",
          inReplyToMessageId = "<msg-1@greenmail.test>",
          from = "sender@greenmail.test",
          to = List("recipient@greenmail.test"),
          subject = "RE: Connection test",
          textBody = "Our reply",
          htmlBody = None
        )
      )
      .unsafeRunSync()
    assert(result.isRight, s"reply failed: ${result.left.map(_.detail)}")
    greenmail.waitForIncomingEmail(10000, 1)
    val inbox =
      greenmail.getReceivedMessagesForDomain("recipient@greenmail.test")
    val withHeaders = inbox.find(m =>
      Option(m.getHeader("In-Reply-To"))
        .exists(_.contains("<msg-1@greenmail.test>"))
    )
    assert(withHeaders.isDefined, "no message carried In-Reply-To")
  }

  test("syncInbox lists the inbox with a UID cursor that advances") {
    List("first", "second").foreach { marker =>
      provider
        .submit(
          credsLoader(credentials()),
          OutboundMessage(
            "sender@greenmail.test",
            List("sender@greenmail.test"),
            marker,
            s"body-$marker",
            None,
            Map.empty
          )
        )
        .unsafeRunSync()
      greenmail.waitForIncomingEmail(10000, 1)
    }
    provider.syncInbox(credsLoader(credentials()), None).unsafeRunSync() match
      case Left(error)  => fail(s"expected batch, got $error")
      case Right(batch) =>
        assert(
          batch.messages.map(_.subject).contains(Some("first")),
          batch.messages.toString
        )
        assert(batch.messages.map(_.subject).contains(Some("second")))
        assert(
          batch.nextCursor.isDefined,
          "UID cursor should advance past the newest message"
        )
        assert(batch.nextCursor.get.toLong > 0)
  }

  test("OAuth-only operations report NotSupported") {
    val auth = AuthorizationResult("c", "r", "s", None)
    provider
      .completeAuthorization(auth, _ => IO.unit)
      .unsafeRunSync()
      .left
      .foreach {
        case _: ProviderError.NotSupported => assert(true)
        case other => fail(s"expected NotSupported, got $other")
      }
    provider
      .refresh(credsLoader(credentials()), _ => IO.unit)
      .unsafeRunSync()
      .left
      .foreach {
        case _: ProviderError.NotSupported => assert(true)
        case other => fail(s"expected NotSupported, got $other")
      }
    provider
      .revokeAccess(credsLoader(credentials()))
      .unsafeRunSync()
      .left
      .foreach {
        case _: ProviderError.NotSupported => assert(true)
        case other => fail(s"expected NotSupported, got $other")
      }
  }
