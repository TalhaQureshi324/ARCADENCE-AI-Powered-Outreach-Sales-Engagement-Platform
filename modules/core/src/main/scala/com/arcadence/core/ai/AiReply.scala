package com.arcadence.core.ai

import io.circe.{Decoder, HCursor}

/** AI Reply Agent (Phase 11.3). Analyzes an authorized conversation and
  * proposes an intent classification, a reply draft, and next actions — as
  * PROPOSALS with evidence references. Deterministic rules always win:
  * unsubscribe/bounce conversations never reach the model at all, external
  * responses require human approval, and evidence citing unknown knowledge is
  * flagged rather than trusted.
  */
object AiReply:

  val intents: Set[String] = Set(
    "interested",
    "pricing",
    "product_question",
    "objection",
    "meeting_request",
    "out_of_office",
    "not_interested",
    "unsubscribe",
    "ambiguous"
  )

  /** Next actions the agent may propose — each maps to a whitelisted
    * deterministic service; nothing here executes directly.
    */
  val allowedActions: Set[String] =
    Set("create_deal", "crm_note", "schedule_meeting", "none")

  final case class NextAction(
      action: String,
      rationale: String,
      factKeys: List[String]
  )
  final case class Evidence(factKey: String, how: String)

  final case class ReplyOutput(
      intent: String,
      replySubject: String,
      replyBody: String,
      nextActions: List[NextAction],
      confidence: String,
      evidence: List[Evidence]
  )

  given Decoder[NextAction] = (c: HCursor) =>
    for
      action <- c.get[String]("action")
      rationale <- c.get[String]("rationale").orElse(Right(""))
      keys <- c.get[List[String]]("factKeys").orElse(Right(Nil))
    yield NextAction(action, rationale, keys)

  given Decoder[Evidence] = (c: HCursor) =>
    for
      factKey <- c.get[String]("factKey")
      how <- c.get[String]("how").orElse(Right(""))
    yield Evidence(factKey, how)

  given Decoder[ReplyOutput] = (c: HCursor) =>
    for
      intent <- c.get[String]("intent")
      subject <- c.get[String]("replySubject")
      body <- c.get[String]("replyBody")
      actions <- c.get[List[NextAction]]("nextActions").orElse(Right(Nil))
      confidence <- c.get[String]("confidence").orElse(Right("low"))
      evidence <- c.get[List[Evidence]]("evidence").orElse(Right(Nil))
    yield ReplyOutput(intent, subject, body, actions, confidence, evidence)

  val promptVersion = "arcadence-reply-v1"

  final case class ThreadMessage(
      direction: String,
      bodyText: String,
      classification: Option[String]
  )

  /** Deterministic prompt assembly: the deterministic classifier's verdict is
    * provided as context (the model may refine intent within the enum but is
    * told unsubscribe/bounce are already handled deterministically), knowledge
    * facts carry stable keys, and the output schema is strict.
    */
  def replyMessages(
      threadSubject: String,
      deterministicClassification: String,
      campaignContext: Option[String],
      contactContext: Option[String],
      knowledge: List[AiWriter.ResearchFact],
      messages: List[ThreadMessage],
      instruction: Option[String]
  ): List[AiGateway.ChatMessage] =
    val factLines =
      if knowledge.isEmpty then
        "No approved knowledge facts. Do not state company specifics."
      else knowledge.map(f => s"- [${f.key}] ${f.claim}").mkString("\n")
    val transcript = messages
      .map(m => s"${m.direction.toUpperCase}: ${m.bodyText.take(1200)}")
      .mkString("\n---\n")
    val system =
      s"""You are Arcadence's reply assistant. Analyze the conversation and propose an intent, a reply draft and next actions.
         |Rules:
         |- intent MUST be one of: ${intents.toList.sorted.mkString(", ")}.
         |- Deterministic systems already handled unsubscribe and bounce (suppression, list hygiene). Never propose replying to those; if the deterministic classification is unsubscribe, recommend no reply.
         |- Reply drafts must be honest; cite only the provided knowledge fact keys in evidence. Never invent company facts, pricing, or commitments.
         |- nextActions entries MUST use action values: ${allowedActions.toList.sorted
          .mkString(", ")}.
         |- Return STRICT JSON: {"intent":"...","replySubject":"...","replyBody":"...","nextActions":[{"action":"...","rationale":"...","factKeys":["f1"]}],"confidence":"low|medium|high","evidence":[{"factKey":"f1","how":"..."}]}
         |- Prompt version: $promptVersion.""".stripMargin
    val user =
      s"""Thread subject: $threadSubject
         |Deterministic classifier verdict: $deterministicClassification
         |${campaignContext.fold("")(c => "Campaign context: " + c)}
         |${contactContext.fold("")(c => "Contact: " + c)}
         |${instruction.fold("")(i => "Operator instruction: " + i)}
         |
         |Approved knowledge facts:
         |$factLines
         |
         |Conversation:
         |$transcript""".stripMargin
    List(
      AiGateway.ChatMessage("system", system),
      AiGateway.ChatMessage("user", user)
    )

  /** Deterministic validation of the model's proposal. Returns flags; an
    * unknown intent is a hard error (the enum is contractual).
    */
  def validate(out: ReplyOutput, providedKeys: Set[String]): List[String] =
    val flags = scala.collection.mutable.ListBuffer.empty[String]
    if !intents.contains(out.intent) then
      flags += s"invalid_intent:${out.intent}"
    flags ++= out.nextActions
      .filterNot(a => allowedActions.contains(a.action))
      .map(a => s"invalid_action:${a.action}")
    val cited =
      out.nextActions.flatMap(_.factKeys) ++ out.evidence.map(_.factKey)
    flags ++= cited
      .filterNot(providedKeys.contains)
      .distinct
      .map(k => s"unsupported_evidence:$k")
    if !Set("low", "medium", "high").contains(out.confidence) then
      flags += "invalid_confidence"
    if out.replyBody.trim.isEmpty then flags += "empty_reply"
    flags.toList

  /** Deterministic short-circuit: these intents are already fully handled by
    * Phase 6 sync rules (suppression, list hygiene). The model is never called
    * for them.
    */
  def deterministicShortCircuit(classification: String): Option[String] =
    val c = classification.toLowerCase
    if c == "unsubscribe" then Some("unsubscribe")
    else if c == "bounce" then Some("bounce")
    else None
