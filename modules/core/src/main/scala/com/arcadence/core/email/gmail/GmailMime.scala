package com.arcadence.core.email.gmail

import java.time.format.DateTimeFormatter
import java.util.Base64

import java.time.OffsetDateTime

/** Minimal RFC 5322 MIME construction for Gmail submission. Text-only or
  * multipart/alternative (text + HTML). Non-ASCII subjects use RFC 2047 base64
  * encoded-words; bodies are always UTF-8. (Attachments arrive with the sending
  * engine — Phase 5.)
  */
object GmailMime:

  final case class Part(
      from: String,
      to: List[String],
      subject: String,
      textBody: String,
      htmlBody: Option[String],
      extraHeaders: Map[String, String] = Map.empty
  )

  def render(part: Part): String =
    val headers = List.newBuilder[String]
    headers += s"From: ${part.from}"
    headers += s"To: ${part.to.mkString(", ")}"
    headers += s"Subject: ${encodeSubject(part.subject)}"
    headers += s"Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(OffsetDateTime.now)}"
    headers += "MIME-Version: 1.0"
    part.extraHeaders.foreach { case (name, value) =>
      headers += s"$name: $value"
    }

    part.htmlBody match
      case None =>
        headers += s"Content-Type: text/plain; charset=UTF-8"
        headers += s"Content-Transfer-Encoding: base64"
        headers.result().mkString("\r\n") + "\r\n\r\n" + base64(part.textBody)
      case Some(html) =>
        val boundary =
          s"arc-${java.util.UUID.randomUUID().toString.replace("-", "")}"
        headers += s"Content-Type: multipart/alternative; boundary=\"$boundary\""
        val textSection =
          s"--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${base64(part.textBody)}"
        val htmlSection =
          s"--$boundary\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${base64(html)}"
        val closing = s"--$boundary--"
        headers.result().mkString("\r\n") + "\r\n\r\n" + List(
          textSection,
          htmlSection,
          closing
        ).mkString("\r\n\r\n")

  /** RFC 2047: non-ASCII subjects become =?UTF-8?B?...?= encoded-words. */
  private def encodeSubject(subject: String): String =
    if subject.forall(c => c >= ' ' && c <= '~') then subject
    else
      val encoded = Base64.getEncoder.encodeToString(
        subject.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
      s"=?UTF-8?B?$encoded?="

  /** Gmail's `raw` field: URL-safe base64 of the RFC 2822 message. */
  def toGmailRaw(part: Part): String =
    Base64.getUrlEncoder.encodeToString(
      render(part).getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  private def base64(text: String): String =
    Base64.getMimeEncoder.encodeToString(
      text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
