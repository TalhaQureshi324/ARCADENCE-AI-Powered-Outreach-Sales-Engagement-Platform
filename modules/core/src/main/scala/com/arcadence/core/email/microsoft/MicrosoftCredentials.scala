package com.arcadence.core.email.microsoft

import cats.syntax.either.*

import com.arcadence.core.email.Credentials
import io.circe.Json
import io.circe.parser.parse

import java.time.OffsetDateTime

/** Stored Microsoft (Graph) OAuth credentials. Microsoft's v2 endpoint always
  * issues a refresh token when `offline_access` is consented, so it is optional
  * only for robustness (e.g. revocation edge cases).
  */
final case class MicrosoftCredentials(
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
  ): MicrosoftCredentials =
    copy(
      accessToken = newAccessToken,
      accessTokenExpiresAt =
        OffsetDateTime.now.plusSeconds(math.max(1L, expiresIn))
    )

object MicrosoftCredentials:

  def fromJson(json: Json): Either[String, MicrosoftCredentials] =
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
        .map(v =>
          try Right(OffsetDateTime.parse(v))
          catch case _: Exception => Left(s"unparsable timestamp: $v")
        )
        .getOrElse(Right(OffsetDateTime.MIN))
    yield MicrosoftCredentials(refresh, accessToken, expiresAt)

  def fromJsonString(text: String): Either[String, MicrosoftCredentials] =
    parse(text)
      .leftMap(e => s"invalid credentials JSON: ${e.message}")
      .flatMap(fromJson)
