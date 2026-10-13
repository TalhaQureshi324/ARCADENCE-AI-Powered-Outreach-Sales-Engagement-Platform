package com.arcadence.core.email.gmail

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

/** MIME construction for Gmail submission (RFC 5322 / RFC 2047 basics). */
final class GmailMimeSpec extends FunSuite:

  test("text-only message renders headers and base64 body") {
    val raw = GmailMime.render(
      GmailMime.Part(
        "from@x.test",
        List("to1@y.test", "to2@y.test"),
        "Hello",
        "Body text",
        None
      )
    )
    assert(raw.contains("From: from@x.test\r\n"))
    assert(raw.contains("To: to1@y.test, to2@y.test\r\n"))
    assert(raw.contains("Subject: Hello\r\n"))
    assert(raw.contains("MIME-Version: 1.0\r\n"))
    assert(raw.contains("Content-Type: text/plain; charset=UTF-8"))
    val body = raw.substring(raw.indexOf("\r\n\r\n") + 4)
    val decoded =
      new String(Base64.getMimeDecoder.decode(body), StandardCharsets.UTF_8)
    assertEquals(decoded, "Body text")
  }

  test("non-ASCII subjects become RFC 2047 encoded-words") {
    val raw = GmailMime.render(
      GmailMime.Part(
        "f@x.test",
        List("t@y.test"),
        "Grüße aus München",
        "b",
        None
      )
    )
    assert(raw.contains("Subject: =?UTF-8?B?"))
    assert(!raw.contains("Grüße")) // raw non-ASCII must not leak into headers
  }

  test("html body produces multipart/alternative with both parts") {
    val raw = GmailMime.render(
      GmailMime.Part(
        "f@x.test",
        List("t@y.test"),
        "Hi",
        "plain",
        Some("<b>rich</b>"),
        Map.empty
      )
    )
    assert(raw.contains("Content-Type: multipart/alternative; boundary=\"arc-"))
    assert(raw.contains("Content-Type: text/plain; charset=UTF-8"))
    assert(raw.contains("Content-Type: text/html; charset=UTF-8"))
    assert(raw.contains("arc-"))
  }

  test("extra headers (In-Reply-To/References) are preserved") {
    val raw = GmailMime.render(
      GmailMime.Part(
        "f@x.test",
        List("t@y.test"),
        "Re: Hi",
        "b",
        None,
        Map("In-Reply-To" -> "<msg-1@x.test>", "References" -> "<msg-1@x.test>")
      )
    )
    assert(raw.contains("In-Reply-To: <msg-1@x.test>"))
    assert(raw.contains("References: <msg-1@x.test>"))
  }

  test(
    "toGmailRaw returns URL-safe base64 that decodes to the rendered message"
  ) {
    val part =
      GmailMime.Part("f@x.test", List("t@y.test"), "s + spaces", "b", None)
    val raw = GmailMime.toGmailRaw(part)
    assert(!raw.contains("+"))
    assert(!raw.contains("/"))
    val decoded =
      new String(Base64.getUrlDecoder.decode(raw), StandardCharsets.UTF_8)
    assertEquals(decoded, GmailMime.render(part))
  }
