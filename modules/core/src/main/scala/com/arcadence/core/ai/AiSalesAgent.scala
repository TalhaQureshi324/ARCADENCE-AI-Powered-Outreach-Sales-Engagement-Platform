package com.arcadence.core.ai

import io.circe.{Decoder, HCursor}

/** AI Sales Agent (Phase 11.4): a CONSTRAINED orchestration. The sequence of
  * steps is fixed by Arcadence's code — the model never plans freely. It only
  * fills structured slots (brief understanding, audience suggestion, campaign
  * draft) through the gateway; every tool step (provider search, enrichment,
  * validation, approval) executes through existing Scala services with
  * workspace authorization and is recorded in ai_run_steps/ai_tool_invocations.
  */
object AiSalesAgent:

  val steps: List[String] = List(
    "interpret_brief",
    "suggest_audience",
    "search_providers",
    "enrich_leads",
    "draft_campaign",
    "validate_readiness",
    "submit_approval"
  )

  final case class BriefUnderstanding(audienceSummary: String, goal: String)
  given Decoder[BriefUnderstanding] = (c: HCursor) =>
    for
      a <- c.get[String]("audienceSummary")
      g <- c.get[String]("goal")
    yield BriefUnderstanding(a, g)

  final case class AudienceSuggestion(
      industry: Option[String],
      region: Option[String],
      jobTitle: Option[String],
      companySize: Option[String]
  )
  given Decoder[AudienceSuggestion] = (c: HCursor) =>
    for
      i <- c.get[Option[String]]("industry")
      r <- c.get[Option[String]]("region")
      j <- c.get[Option[String]]("jobTitle")
      s <- c.get[Option[String]]("companySize")
    yield AudienceSuggestion(i, r, j, s)

  val promptVersion = "arcadence-sales-agent-v1"

  def briefMessages(
      objective: String,
      offer: String,
      persona: String
  ): List[AiGateway.ChatMessage] =
    List(
      AiGateway.ChatMessage(
        "system",
        s"""You are Arcadence's sales agent planner. Return STRICT JSON only.
           |Schema: {"audienceSummary":"...","goal":"..."}. Prompt version: $promptVersion.""".stripMargin
      ),
      AiGateway.ChatMessage(
        "user",
        s"Campaign objective: $objective\nOffer: $offer\nTarget persona: $persona"
      )
    )

  def audienceMessages(
      understanding: BriefUnderstanding
  ): List[AiGateway.ChatMessage] =
    List(
      AiGateway.ChatMessage(
        "system",
        """Suggest B2B lead filters. Return STRICT JSON only.
          |Schema: {"industry":"...|null","region":"...|null","jobTitle":"...|null","companySize":"...|null"}.""".stripMargin
      ),
      AiGateway.ChatMessage(
        "user",
        s"Audience: ${understanding.audienceSummary}\nGoal: ${understanding.goal}"
      )
    )

  /** Deterministic readiness verdict over the run's artifacts. */
  def readiness(
      contactsFound: Int,
      drafts: List[(AiWriter.Draft, List[String])],
      budgetTokensLeft: Long
  ): (Boolean, List[String]) =
    val issues = scala.collection.mutable.ListBuffer.empty[String]
    if contactsFound == 0 then issues += "no_provider_results"
    if drafts.isEmpty then issues += "no_drafts"
    drafts.foreach { case (_, flags) =>
      flags.foreach(f => issues += s"draft_flag:$f")
    }
    if budgetTokensLeft <= 0 then issues += "budget_exhausted"
    (issues.isEmpty, issues.toList)
