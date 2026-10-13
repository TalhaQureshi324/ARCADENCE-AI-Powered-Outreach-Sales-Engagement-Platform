package com.arcadence.core.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.duration.FiniteDuration

/** Tamper-proof OAuth `state` parameter (Phase 2.2): base64url(payload) + "." +
  * base64url(HMAC-SHA256(payload)). The payload carries the workspace,
  * initiating user, PKCE verifier and an absolute expiry — nothing sensitive
  * (the verifier is single-use and only valid with this flow's code), but it
  * must not be forgeable or mutable. Signature comparison is constant-time.
  */
final class OAuthStateSigner(secret: Array[Byte]):

  def sign(payload: String): String =
    val encoded = base64Url(payload.getBytes(StandardCharsets.UTF_8))
    val mac = hmac(payload.getBytes(StandardCharsets.UTF_8))
    s"$encoded.${base64Url(mac)}"

  def verify(token: String): Either[StateInvalid, String] =
    token.split('.') match
      case Array(encoded, signature) =>
        val payloadBytes = decodeBase64Url(encoded)
        if payloadBytes == null then Left(StateInvalid.Malformed)
        else
          val expected = hmac(payloadBytes)
          if !MessageDigest.isEqual(expected, decodeBase64Url(signature))
          then Left(StateInvalid.BadSignature)
          else Right(new String(payloadBytes, StandardCharsets.UTF_8))
      case _ => Left(StateInvalid.Malformed)

  private def hmac(data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(data)

  private def base64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def decodeBase64Url(text: String): Array[Byte] =
    try Base64.getUrlDecoder.decode(text)
    catch case _: IllegalArgumentException => null

object OAuthStateSigner:

  def decodePayloadJson(payload: String): Either[StateInvalid, io.circe.Json] =
    io.circe.parser.parse(payload) match
      case Right(json) => Right(json)
      case Left(_)     => Left(StateInvalid.Malformed)

enum StateInvalid:
  case Malformed
  case BadSignature
  case Expired
  case MissingField(field: String)
