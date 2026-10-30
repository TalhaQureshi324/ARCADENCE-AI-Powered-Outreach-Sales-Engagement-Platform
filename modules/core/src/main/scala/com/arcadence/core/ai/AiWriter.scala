package com.arcadence.core.ai

import io.circe.{Decoder, HCursor}

/** AI email writer and lead researcher (Phase 11.2). The model produces
  * structured drafts; everything else here is deterministic: prompt assembly,
  * schema validation, grounded-claim checking, deception scans and template
  * rendering. Claims must cite provided research keys — anything else is
  * flagged as unsupported, never silently trusted.
  */
object AiWriter:

  final case class WriterBrief(
      objective: String,
      offer: String,
      persona: String,
      tone: String,
      cta: String,
      length: String, // short | medium | long
      personalizationLevel: String // none | light | deep
  )

  final case class ResearchFact(
      key: String, // stable key given to the model, e.g. "f1"
      claim: String,
      sourceUrl: Option[String],
      retrievedAt: Option[String]
  )

  final case class DraftClaim(factKey: String, quote: String)
  final case class Draft(
      subject: String,
      body: String,
      claims: List[DraftClaim]
  )

  final case class WriterOutput(variations: List[Draft])

  given Decoder[DraftClaim] = (c: HCursor) =>
    for
      k <- c.get[String]("factKey")
      quote <- c.get[String]("quote")
    yield DraftClaim(k, quote)

  given Decoder[Draft] = (c: HCursor) =>
    for
      subject <- c.get[String]("subject")
      body <- c.get[String]("body")
      claims <- c.get[List[DraftClaim]]("claims").orElse(Right(Nil))
    yield Draft(subject, body, claims)

  given Decoder[WriterOutput] = (c: HCursor) =>
    for variations <- c.get[List[Draft]]("drafts")
    yield WriterOutput(variations)

  val promptVersion = "arcadence-writer-v1"

  val knownVariables: Set[String] =
    Set(
      "first_name",
      "last_name",
      "company",
      "job_title",
      "sender_name",
      "sender_company"
    )

  /** Deterministic prompt assembly. Facts are passed with their stable keys and
    * provenance; the model must cite keys and never invent facts. The JSON
    * schema of the expected output is stated explicitly so malformed responses
    * fail validation instead of guessing.
    */
  def briefMessages(
      brief: WriterBrief,
      facts: List[ResearchFact],
      variations: Int
  ): List[AiGateway.ChatMessage] =
    val factLines =
      if facts.isEmpty then
        "No research facts are available. Do not reference any company specifics."
      else
        facts
          .map { f =>
            val src = f.sourceUrl.fold("no source url")(u => s"source: $u")
            val at = f.retrievedAt.fold("")(t => s" (retrieved $t)")
            s"- [${f.key}] ${f.claim} $src$at"
          }
          .mkString("\n")
    val system =
      s"""You are Arcadence's B2B outreach copywriter. Write cold emails that are honest and specific.
         |Rules:
         |- Ground every company-specific claim in the numbered research facts and cite their keys in the claims array.
         |- Never invent statistics, names, funding, customers or product capabilities. Uncited company claims are forbidden.
         |- Respect the requested tone, length and CTA. No deceptive pressure phrases.
         |- You may use these template variables only: ${knownVariables.toList.sorted
          .map(v => "{{" + v + "}}")
          .mkString(", ")}.
         |- Return STRICT JSON: {"drafts":[{"subject":"...","body":"...","claims":[{"factKey":"f1","quote":"..."}]}]}
         |- Exactly $variations draft(s), each meaningfully different. Prompt version: $promptVersion.""".stripMargin
    val user =
      s"""Campaign objective: ${brief.objective}
         |What we offer: ${brief.offer}
         |Target persona: ${brief.persona}
         |Tone: ${brief.tone}
         |Call to action: ${brief.cta}
         |Length: ${brief.length}
         |Personalization level: ${brief.personalizationLevel}
         |
         |Approved research facts (cite by key, never contradict them):
         |$factLines""".stripMargin
    List(
      AiGateway.ChatMessage("system", system),
      AiGateway.ChatMessage("user", user)
    )

  /** Claims citing fact keys that were not provided are unsupported. */
  def unsupportedClaims(draft: Draft, providedKeys: Set[String]): List[String] =
    draft.claims.map(_.factKey).filterNot(providedKeys.contains).distinct

  /** Deterministic deception scan — flagged for human review, never sent. */
  def deceptivePhrases(text: String): List[String] =
    val patterns = List(
      "guaranteed income",
      "100% guaranteed",
      "risk-free",
      "act now",
      "limited time only",
      "make money fast",
      "no strings attached",
      "this is not spam",
      "you must respond"
    )
    val lower = text.toLowerCase
    patterns.filter(lower.contains)

  /** Template variables left in the text that Arcadence cannot render. */
  def unknownVariables(text: String): List[String] =
    "\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}".r
      .findAllMatchIn(text)
      .map(m => m.group(1))
      .filterNot(knownVariables.contains)
      .toList
      .distinct

  /** Deterministic final-variable rendering (ADR-0026 quoteReplacement).
    * Unknown variables or empty values fail loudly instead of reaching a
    * contact with a broken placeholder.
    */
  def render(
      text: String,
      variables: Map[String, String]
  ): Either[String, String] =
    val unknown = unknownVariables(text)
    if unknown.nonEmpty then
      Left(s"unrenderable variables: ${unknown.mkString(", ")}")
    else
      val missing = variables.filter(_._2.trim.isEmpty).keys.toList
      if missing.nonEmpty then
        Left(s"empty variable values: ${missing.mkString(", ")}")
      else
        val replaced =
          variables.foldLeft(text) { case (acc, (k, v)) =>
            ("\\{\\{\\s*" + scala.util.matching.Regex.quote(k) + "\\s*\\}\\}").r
              .replaceAllIn(acc, scala.util.matching.Regex.quoteReplacement(v))
          }
        // A variable that was never supplied must never reach a contact as a
        // leftover placeholder — fail loudly instead.
        val leftover =
          "\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}".r
            .findAllMatchIn(replaced)
            .map(m => m.group(1))
            .toList
            .distinct
        if leftover.nonEmpty then
          Left(s"unrendered variables: ${leftover.mkString(", ")}")
        else Right(replaced)

  /** Full deterministic evaluation of a generated draft. */
  def evaluate(draft: Draft, providedKeys: Set[String]): List[String] =
    val flags = scala.collection.mutable.ListBuffer.empty[String]
    flags ++= unsupportedClaims(draft, providedKeys).map(k =>
      s"unsupported_claim:$k"
    )
    flags ++= unknownVariables(draft.subject).map(v => s"unknown_variable:$v")
    flags ++= unknownVariables(draft.body).map(v => s"unknown_variable:$v")
    flags ++= deceptivePhrases(draft.subject).map(p => s"deceptive_phrase:$p")
    flags ++= deceptivePhrases(draft.body).map(p => s"deceptive_phrase:$p")
    if draft.subject.trim.isEmpty then flags += "empty_subject"
    if draft.body.trim.isEmpty then flags += "empty_body"
    flags.toList
