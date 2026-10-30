package com.arcadence.core.ai

import io.circe.parser.parse
import munit.FunSuite

/** Writer determinism checks (Phase 11.2): grounded-claim validation, deception
  * scan, variable rendering, and structured output decoding.
  */
final class AiWriterSpec extends FunSuite:

  private val facts = List(
    AiWriter.ResearchFact(
      "f1",
      "Acme is scaling its sales team",
      Some("https://example.com/news"),
      Some("2026-10-01")
    ),
    AiWriter.ResearchFact("f2", "Acme opened a Madrid office", None, None)
  )

  test("brief prompt embeds facts with keys, provenance and output schema") {
    val msgs = AiWriter.briefMessages(
      AiWriter.WriterBrief(
        "book demos",
        "CRM",
        "VP Sales",
        "direct",
        "15 min call",
        "short",
        "deep"
      ),
      facts,
      2
    )
    val system = msgs.head.content
    val user = msgs.last.content
    assert(
      system.contains("[f1] Acme is scaling its sales team") || user.contains(
        "[f1]"
      )
    )
    assert(user.contains("https://example.com/news"))
    assert(system.contains("Never invent"))
    assert(system.contains("arcadence-writer-v1"))
    assert(system.contains("\"drafts\""))
    assertEquals(msgs.map(_.role), List("system", "user"))
  }

  test("claims citing unprovided fact keys are flagged as unsupported") {
    val draft = AiWriter.Draft(
      "s",
      "b",
      List(AiWriter.DraftClaim("f1", "q"), AiWriter.DraftClaim("f9", "q"))
    )
    assertEquals(AiWriter.unsupportedClaims(draft, Set("f1", "f2")), List("f9"))
  }

  test("deception scan catches pressure phrases in subject and body") {
    assert(
      AiWriter
        .deceptivePhrases("Act NOW for 100% guaranteed results")
        .contains("act now")
    )
    assert(
      AiWriter.deceptivePhrases("This is not spam").contains("this is not spam")
    )
    assertEquals(
      AiWriter.deceptivePhrases("Quick question about your team"),
      Nil
    )
  }

  test("unknown template variables are detected; known ones pass") {
    assertEquals(
      AiWriter.unknownVariables("Hi {{first_name}} at {{unicorn_name}}"),
      List("unicorn_name")
    )
    assertEquals(AiWriter.unknownVariables("Hi {{first_name}}"), Nil)
  }

  test("render is deterministic and safe for replacement-hostile values") {
    val out = AiWriter.render(
      "Hi {{first_name}} from {{company}}",
      Map("first_name" -> "Ada", "company" -> "Acme $1 (deals)")
    )
    assertEquals(out, Right("Hi Ada from Acme $1 (deals)"))
    assert(AiWriter.render("Hi {{dragon}}", Map("first_name" -> "x")).isLeft)
    assert(
      AiWriter.render("Hi {{first_name}}", Map("first_name" -> "  ")).isLeft
    )
    assert(
      AiWriter
        .render("Hi {{first_name}} at {{company}}", Map("first_name" -> "Ada"))
        .isLeft
    )
  }

  test("evaluate aggregates all deterministic flags") {
    val draft = AiWriter.Draft(
      "Act now",
      "100% guaranteed {{unicorn_name}}",
      List(AiWriter.DraftClaim("f9", "?"))
    )
    val flags = AiWriter.evaluate(draft, Set("f1"))
    assert(flags.contains("unsupported_claim:f9"))
    assert(flags.contains("unknown_variable:unicorn_name"))
    assert(flags.contains("deceptive_phrase:act now"))
    assert(flags.contains("deceptive_phrase:100% guaranteed"))
  }

  test("writer output decodes from the documented JSON schema") {
    val json = parse("""{"drafts":[
        {"subject":"Hi","body":"Grounded.","claims":[{"factKey":"f1","quote":"scaling"}]},
        {"subject":"Plain","body":"No claims.","claims":[]}
      ]}""").toOption.get
    val decoded = json.as[AiWriter.WriterOutput]
    assert(decoded.isRight)
    assertEquals(decoded.map(_.variations.size), Right(2))
    assertEquals(
      decoded.map(_.variations.head.claims.head.factKey),
      Right("f1")
    )
  }
