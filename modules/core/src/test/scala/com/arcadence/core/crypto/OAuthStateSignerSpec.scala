package com.arcadence.core.crypto

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

/** OAuth state signing: forgery and tampering resistance (Phase 2.2). */
final class OAuthStateSignerSpec extends FunSuite:

  private def signer =
    new OAuthStateSigner(
      "test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    )

  test("sign/verify round-trips the payload") {
    val payload = """{"ws":"abc","uid":"42","exp":9999999999,"v":"verifier"}"""
    val token = signer.sign(payload)
    assertEquals(signer.verify(token), Right(payload))
  }

  test("a flipped payload byte invalidates the signature") {
    val payload = """{"ws":"abc","uid":"42","exp":9999999999,"v":"verifier"}"""
    val token = signer.sign(payload)
    val Array(encoded, signature) = token.split('.')
    val tampered = Base64.getUrlEncoder.withoutPadding.encodeToString(
      """{"ws":"evil","uid":"42","exp":9999999999,"v":"verifier"}""".getBytes(
        StandardCharsets.UTF_8
      )
    )
    assertEquals(
      signer.verify(s"$tampered.$signature"),
      Left(StateInvalid.BadSignature)
    )
  }

  test("a signature from a different secret is rejected") {
    val payload = """{"ws":"abc"}"""
    val foreign = new OAuthStateSigner(
      "other-secret-9876543210".getBytes(StandardCharsets.UTF_8)
    )
    val token = foreign.sign(payload)
    assertEquals(signer.verify(token), Left(StateInvalid.BadSignature))
  }

  test("garbage tokens are Malformed, not exceptions") {
    assertEquals(signer.verify(""), Left(StateInvalid.Malformed))
    assertEquals(signer.verify("no-dots-here"), Left(StateInvalid.Malformed))
    assertEquals(signer.verify("!!!.???"), Left(StateInvalid.Malformed))
  }
