package com.arcadence.core.email

import munit.FunSuite

/** Capability matrix consistency: every provider id has capabilities, all
  * providers can send, summaries are meaningful, and the id parser matches.
  */
final class ProviderCapabilitiesSpec extends FunSuite:

  test("every provider id resolves to an entry in the catalog") {
    EmailProvider.All.foreach { id =>
      assert(
        ProviderCapabilities.forProvider(id).isDefined,
        s"missing capabilities for $id"
      )
    }
    assertEquals(ProviderCapabilities.All.map(_.provider), EmailProvider.All)
  }

  test(
    "forProvider rejects unknown ids consistently with EmailProvider.fromId"
  ) {
    assertEquals(ProviderCapabilities.forProvider("carrier_pigeon"), None)
    assert(EmailProvider.fromId("carrier_pigeon").isLeft)
    assertEquals(EmailProvider.fromId("gmail"), Right(EmailProvider.Gmail))
  }

  test("OAuth providers refresh tokens; SMTP/IMAP does not") {
    val gmail = ProviderCapabilities.forProvider(EmailProvider.Gmail).get
    val micros = ProviderCapabilities.forProvider(EmailProvider.Microsoft).get
    val smtp = ProviderCapabilities.forProvider(EmailProvider.SmtpImap).get
    assert(gmail.canRefreshToken && micros.canRefreshToken)
    assert(!smtp.canRefreshToken)
    assertEquals(gmail.authType, AuthType.OAuth)
    assertEquals(smtp.authType, AuthType.PasswordTls)
  }

  test("all providers expose send + inbox + health capabilities for parity") {
    ProviderCapabilities.All.foreach { c =>
      assert(c.canSend, s"${c.provider} must send")
      assert(c.canSyncInbox, s"${c.provider} must sync inbox")
      assert(c.canSendReply, s"${c.provider} must reply")
      assert(c.hasHealthCheck, s"${c.provider} must health-check")
    }
  }

  test(
    "permission summaries are non-trivial and connect flows are documented"
  ) {
    ProviderCapabilities.All.foreach { c =>
      assert(
        c.permissionsSummary.length > 40,
        s"${c.provider} summary too thin"
      )
      assert(
        c.connectFlow.startsWith("subphase 2."),
        s"${c.provider} flow undocumented"
      )
    }
  }
