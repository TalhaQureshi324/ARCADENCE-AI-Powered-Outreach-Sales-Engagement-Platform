package com.arcadence.core.email.gmail

import cats.syntax.either.*

import com.arcadence.core.email.Credentials
import io.circe.Json
import io.circe.parser.parse

import java.time.OffsetDateTime

/** Stored Gmail OAuth credentials. Serialized to JSON, then encrypted at rest
  * by the storage layer (ADR-0020) — the adapter never sees ciphertext.
  *
  * @param refreshToken
  *   absent when Google did not issue a new one (re-consent flows reuse the
  *   previously stored token — callers must merge)
  */
final case class GmailCredentials(
    refreshToken: Option[String],
    accessToken: String,
    accessTokenExpiresAt: OffsetDateTime
) extends Credentials:

  def toJson: Json =
    Json.obj(
      "refresh_token" -> refreshToken.map(Json.fromString).getOrElse(Json.Null),
      "access_token" -> Json.fromString(accessToken),
      "access_token_expires_at" -> Json.fromString(
        accessTokenExpiresAt.toString
      )
    )

  def withRefreshedAccess(
      newAccessToken: String,
      expiresIn: Long
  ): GmailCredentials =
    copy(
      accessToken = newAccessToken,
      accessTokenExpiresAt =
        OffsetDateTime.now.plusSeconds(expiresAtBound(expiresIn))
    )

  private def expiresAtBound(seconds: Long): Long = math.max(1L, seconds)

object GmailCredentials:

  def fromJson(json: Json): Either[String, GmailCredentials] =
    for
      accessToken <- json.hcursor
        .get[String]("access_token")
        .left
        .map(_ => "access_token missing")
      refresh <- json.hcursor
        .get[Option[String]]("refresh_token")
        .left
        .map(_ => "refresh_token malformed")
      expires <- json.hcursor
        .get[Option[String]]("access_token_expires_at")
        .left
        .map(_ => "access_token_expires_at malformed")
      expiresAt <- expires
        .map(parseInstant)
        .getOrElse(Right(OffsetDateTime.MIN))
    yield GmailCredentials(refresh, accessToken, expiresAt)

  def fromJsonString(text: String): Either[String, GmailCredentials] =
    parse(text)
      .leftMap(e => s"invalid credentials JSON: ${e.message}")
      .flatMap(fromJson)

  private def parseInstant(value: String): Either[String, OffsetDateTime] =
    try Right(OffsetDateTime.parse(value))
    catch case _: Exception => Left(s"unparsable timestamp: $value")
