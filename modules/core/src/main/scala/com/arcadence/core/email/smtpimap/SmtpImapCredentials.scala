package com.arcadence.core.email.smtpimap

import cats.syntax.either.*

import com.arcadence.core.email.Credentials
import io.circe.Json
import io.circe.parser.parse

/** Connection parameters for one SMTP/IMAP account. Stored (encrypted) as the
  * account's credentials — the password NEVER leaves the server.
  *
  * @param security
  *   `ssl` (implicit TLS) or `starttls` (upgrade). Plaintext is deliberately
  *   unsupported (AGENTS.md §5 / ADR-0023).
  */
final case class SmtpImapCredentials(
    emailAddress: String,
    username: String,
    password: String,
    smtpHost: String,
    smtpPort: Int,
    smtpSecurity: String,
    imapHost: String,
    imapPort: Int,
    imapSecurity: String
) extends Credentials:

  def toJson: Json =
    Json.obj(
      "email_address" -> Json.fromString(emailAddress),
      "username" -> Json.fromString(username),
      "password" -> Json.fromString(password),
      "smtp_host" -> Json.fromString(smtpHost),
      "smtp_port" -> Json.fromInt(smtpPort),
      "smtp_security" -> Json.fromString(smtpSecurity),
      "imap_host" -> Json.fromString(imapHost),
      "imap_port" -> Json.fromInt(imapPort),
      "imap_security" -> Json.fromString(imapSecurity)
    )

object SmtpImapCredentials:

  val SecurityModes: List[String] = List("ssl", "starttls")

  def validSecurity(mode: String): Boolean = SecurityModes.contains(mode)

  /** Basic shape validation before any network attempt (fail fast, no leak). */
  def validate(c: SmtpImapCredentials): Either[String, Unit] =
    if !c.emailAddress.contains('@') then
      Left("email_address is not a valid email")
    else if c.username.trim.isEmpty || c.password.isEmpty then
      Left("username and password are required")
    else if !validSecurity(c.smtpSecurity) then
      Left("smtp_security must be ssl or starttls")
    else if !validSecurity(c.imapSecurity) then
      Left("imap_security must be ssl or starttls")
    else if c.smtpHost.trim.isEmpty || c.imapHost.trim.isEmpty then
      Left("smtp_host and imap_host are required")
    else if !(1 to 65535).contains(c.smtpPort) || !(1 to 65535).contains(
        c.imapPort
      )
    then Left("ports must be within 1–65535")
    else Right(())

  def fromJson(json: Json): Either[String, SmtpImapCredentials] =
    def getStr(field: String): Either[String, String] =
      json.hcursor.get[String](field).left.map(_ => s"$field missing")
    def getInt(field: String): Either[String, Int] =
      json.hcursor.get[Int](field).left.map(_ => s"$field missing")
    for
      emailAddress <- getStr("email_address")
      username <- getStr("username")
      password <- getStr("password")
      smtpHost <- getStr("smtp_host")
      smtpPort <- getInt("smtp_port")
      smtpSecurity <- getStr("smtp_security")
      imapHost <- getStr("imap_host")
      imapPort <- getInt("imap_port")
      imapSecurity <- getStr("imap_security")
      creds = SmtpImapCredentials(
        emailAddress,
        username,
        password,
        smtpHost,
        smtpPort,
        smtpSecurity,
        imapHost,
        imapPort,
        imapSecurity
      )
      _ <- validate(creds)
    yield creds

  def fromJsonString(text: String): Either[String, SmtpImapCredentials] =
    parse(text)
      .leftMap(e => s"invalid credentials JSON: ${e.message}")
      .flatMap(fromJson)
