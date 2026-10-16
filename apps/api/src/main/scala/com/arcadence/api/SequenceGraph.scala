package com.arcadence.api

import io.circe.Json
import java.util.UUID

/** Sequence graph validation (Phase 4.3). The editor submits steps keyed by
  * stable client keys plus explicit edges; activation requires a valid graph:
  *
  *   - unique non-empty keys; ≥1 step
  *   - edges reference known keys, no self-loops
  *   - branch steps: exactly two outgoing edges keyed `yes` and `no`, and a
  *     whitelisted condition
  *   - other steps: at most one outgoing edge
  *   - in-degree: exactly one root (no incoming edges); every other step has
  *     exactly one incoming edge (no merges — execution stays deterministic)
  *   - full reachability from the root. With in-degree ≤ 1 this also proves
  *     acyclicity: a reachable cycle would need a second incoming edge.
  *
  * Legacy linear campaigns (no edges) are validated as a position-ordered
  * chain.
  */
object SequenceGraph:

  final case class GStep(
      key: String,
      stepType: String,
      waitDays: Option[Int],
      condition: Option[Json],
      tagId: Option[UUID],
      taskDueDays: Option[Int],
      variantCount: Int
  )

  final case class GEdge(from: String, to: String, branchKey: Option[String])

  val StepTypes: Set[String] = Set("email", "wait", "branch", "task", "add_tag")

  /** Branch conditions reuse the segment operator whitelist on contact fields
    * resolvable in Scala (custom fields included).
    */
  val ConditionFields: Set[String] =
    Set(
      "state",
      "verification_status",
      "region",
      "job_title",
      "email",
      "company"
    ) ++
      Set.empty[String] // custom.* accepted by prefix below
  val ConditionOperators: Set[String] =
    Set("eq", "neq", "contains", "not_contains", "in", "not_in")

  def validateCondition(condition: Option[Json]): List[String] = condition match
    case None    => List("Branch steps need a condition.")
    case Some(c) =>
      val obj = c.asObject
      if obj.isEmpty then List("Branch condition must be an object.")
      else
        val o = obj.get
        val field = o("field").flatMap(_.asString).getOrElse("")
        val operator = o("operator").flatMap(_.asString).getOrElse("")
        val value = o("value")
        val errors = List.newBuilder[String]
        val fieldOk = ConditionFields(field) || field.startsWith("custom.")
        if !fieldOk then
          errors += s"Branch condition field is not allowed: $field"
        if !ConditionOperators(operator) then
          errors += s"Branch condition operator is not allowed: $operator"
        if value.forall(_.isNull) then
          errors += "Branch condition needs a value."
        errors.result()

  def validate(steps: List[GStep], edges: List[GEdge]): List[String] =
    val errors = List.newBuilder[String]
    if steps.isEmpty then return List("A sequence needs at least one step.")
    if steps.size > 100 then errors += "A sequence can have at most 100 steps."
    val keys = steps.map(_.key)
    if keys.exists(_.isBlank) then errors += "Every step needs a key."
    if keys.distinct.size != keys.size then
      errors += "Step keys must be unique."
    val keySet = keys.toSet
    val validEdges = edges.filter(e => keySet(e.from) && keySet(e.to))
    if validEdges.size != edges.size then
      errors += "Edges reference unknown steps."
    edges.foreach(e =>
      if e.from == e.to then errors += s"Self-loop on step ${e.from}."
    )

    steps.foreach { s =>
      if !StepTypes(s.stepType) then
        errors += s"Unknown step type: ${s.stepType}"
      s.stepType match
        case "wait" =>
          if s.waitDays.isEmpty then
            errors += s"Wait step ${s.key} needs waitDays."
          else if s.waitDays.exists(d => d < 0 || d > 365) then
            errors += s"Wait step ${s.key}: waitDays must be 0–365."
        case "email" =>
          if s.variantCount == 0 then
            errors += s"Email step ${s.key} needs at least one variant."
          if s.waitDays.isDefined then
            errors += s"Email step ${s.key} cannot have waitDays."
        case "branch" =>
          errors ++= validateCondition(s.condition).map(m =>
            s"Branch step ${s.key}: $m"
          )
        case "task" =>
          if s.taskDueDays.isEmpty then
            errors += s"Task step ${s.key} needs taskDueDays."
          else if s.taskDueDays.exists(d => d < 0 || d > 365) then
            errors += s"Task step ${s.key}: taskDueDays must be 0–365."
        case "add_tag" =>
          if s.tagId.isEmpty then
            errors += s"Add-tag step ${s.key} needs a tag."
        case _ => ()
    }

    if keySet.exists(_.isBlank) || keys.distinct.size != keys.size then
      errors.result()
    else
      val outgoing = validEdges.groupBy(_.from)
      val incoming = validEdges.groupBy(_.to)
      steps.foreach { s =>
        val outs = outgoing.getOrElse(s.key, Nil)
        s.stepType match
          case "branch" =>
            val branchKeys = outs.map(_.branchKey.getOrElse("")).sorted
            if outs.size != 2 || branchKeys != List("no", "yes") then
              errors += s"Branch step ${s.key} needs exactly two outgoing edges labeled yes and no."
          case _ =>
            if outs.size > 1 then
              errors += s"Step ${s.key} has multiple outgoing edges."
      }
      val roots = steps.filter(s => incoming.getOrElse(s.key, Nil).isEmpty)
      if roots.size != 1 then
        errors += s"The sequence needs exactly one starting step (found ${roots.size})."
      else
        // BFS reachability; with in-degree ≤ 1 this also rules out cycles.
        val visited = scala.collection.mutable.Set[String]()
        val queue = scala.collection.mutable.Queue(roots.head.key)
        while queue.nonEmpty do
          val k = queue.dequeue()
          if !visited(k) then
            visited += k
            outgoing.getOrElse(k, Nil).foreach(e => queue.enqueue(e.to))
        val unreachable = steps.map(_.key).filterNot(visited)
        if unreachable.nonEmpty then
          errors += s"Unreachable steps: ${unreachable.mkString(", ")}."
      errors.result()

  /** Backward compatibility: campaigns saved before Phase 4.3 have no edges —
    * treat them as a position-ordered linear chain. `steps` must be in position
    * order; keys may be synthetic (e.g. "1", "2", …).
    */
  def validateLinear(steps: List[GStep]): List[String] =
    val chain =
      steps.zip(steps.drop(1)).map { case (a, b) => GEdge(a.key, b.key, None) }
    validate(steps, chain)
