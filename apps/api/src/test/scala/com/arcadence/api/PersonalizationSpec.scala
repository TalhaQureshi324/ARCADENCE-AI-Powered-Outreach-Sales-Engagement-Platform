package com.arcadence.api

import io.circe.Json
import munit.FunSuite

/** Personalization engine (Phase 4.2): token extraction, fallbacks, missing
  * reporting (never fabricated), regex-safe value insertion, HTML sanitization
  * and plaintext derivation.
  */
final class PersonalizationSpec extends FunSuite:

  test("tokens extracts distinct fields with fallbacks in order") {
    val t = Personalization.tokens(
      "Hi {{ first_name }}, {{first_name|there}} — {{custom.industry}} {{last_name}}"
    )
    assertEquals(
      t.map(_._1),
      List("first_name", "custom.industry", "last_name")
    )
    assertEquals(t.head._2, None)
    assertEquals(
      Personalization.tokens("{{first_name|there}}").head._2,
      Some("there")
    )
  }

  test("unknownTokens flags typo fields but not custom.*") {
    val u = Personalization.unknownTokens(
      "{{first_name}} {{comapny}} {{custom.industry}}"
    )
    assertEquals(u, List("comapny"))
  }

  test("render substitutes values, applies fallbacks, reports missing") {
    val resolver: String => Option[String] = {
      case "first_name" => Some("Ada")
      case "company"    => Some("")
      case _            => None
    }
    val r = Personalization.render(
      "Hi {{first_name}} at {{company|your company}}{{last_name}}!",
      resolver
    )
    assertEquals(r.text, "Hi Ada at your company!")
    assertEquals(r.fellBack, List("company"))
    assertEquals(r.missing, List("last_name"))
  }

  test("render never treats lead data as replacement syntax") {
    val r = Personalization.render("Hi {{first_name}}", _ => Some("$& $1 \\"))
    assertEquals(r.text, "Hi $& $1 \\")
  }

  test("sanitizeHtml strips scripts, handlers and iframes, keeps links") {
    val dirty =
      """<p onclick="steal()">Hi <script>alert(1)</script><img src=x onerror=alert(2)>there</p>
        |<iframe src="https://evil"></iframe><a href="https://ok.example">link</a>""".stripMargin
    val clean = Personalization.sanitizeHtml(dirty)
    assert(!clean.contains("script"))
    assert(!clean.contains("onerror"))
    assert(!clean.contains("onclick"))
    assert(!clean.contains("iframe"))
    assert(clean.contains("https://ok.example"))
    assert(clean.contains("there"))
  }

  test("htmlToText derives readable plaintext from sanitized html") {
    val text = Personalization.htmlToText(
      "<p>Line one</p><p>Line <b>two</b></p><script>x</script>"
    )
    assert(text.contains("Line one"))
    assert(text.contains("Line two"))
    assert(!text.contains("x"))
  }

  test("custom.* resolver reads jsonb fields with scalar coercion") {
    val custom = Json.obj(
      "industry" -> Json.fromString("SaaS"),
      "size" -> Json.fromInt(42)
    )
    val resolver = Personalization.resolverFor(
      email = "a@b.test",
      firstName = None,
      lastName = None,
      company = None,
      jobTitle = None,
      region = None,
      timezone = None,
      website = None,
      phone = None,
      customFields = custom
    )
    assertEquals(resolver("custom.industry"), Some("SaaS"))
    assertEquals(resolver("custom.size"), Some("42"))
    assertEquals(resolver("custom.missing"), None)
    assertEquals(resolver("first_name"), None)
  }
