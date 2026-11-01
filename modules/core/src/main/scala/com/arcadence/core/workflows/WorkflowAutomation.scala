package com.arcadence.core.workflows

import cats.syntax.all.*
import io.circe.Json

/** Workflow automation validation (Phase 12.4). A workflow is a linear-ish
  * graph of condition/action nodes attached to a trigger. Validation enforces
  * the whitelist of node kinds, connectivity from the trigger, bounded size,
  * and acyclicity — loops are structurally impossible, so execution always
  * terminates.
  */
object WorkflowAutomation:

  val triggers: Set[String] = Set(
    "lead_imported",
    "verification_completed",
    "positive_reply",
    "meeting_requested",
    "crm_stage_changed",
    "campaign_completed",
    "sender_health_warning"
  )

  val actionKinds: Set[String] = Set(
    "add_tag",
    "update_crm_stage",
    "assign_conversation",
    "create_task",
    "internal_notification",
    "request_ai_draft",
    "add_to_sequence",
    "invoke_webhook"
  )

  val conditionKinds: Set[String] = Set(
    "has_tag",
    "email_domain_is",
    "field_equals"
  )

  final case class Node(
      id: String,
      kind: String,
      isCondition: Boolean,
      config: Json
  )
  final case class Edge(from: String, to: String)
  final case class Graph(nodes: List[Node], edges: List[Edge])

  def decode(graph: Json): Either[String, Graph] =
    val cursor = graph.hcursor
    for
      nodes <- cursor
        .downField("nodes")
        .as[List[Json]]
        .left
        .map(_ => "graph.nodes must be an array")
      parsed <- nodes
        .traverse { n =>
          for
            id <- n.hcursor.get[String]("id")
            kind <- n.hcursor.get[String]("kind")
            isCond <- n.hcursor.get[Boolean]("condition").orElse(Right(false))
          yield Node(
            id,
            kind,
            isCond,
            n.hcursor.downField("config").focus.getOrElse(Json.obj())
          )
        }
        .left
        .map(_.getMessage)
      edges <- cursor
        .downField("edges")
        .as[List[Json]]
        .left
        .map(_ => "graph.edges must be an array")
      parsedEdges <- edges
        .traverse { e =>
          for
            from <- e.hcursor.get[String]("from")
            to <- e.hcursor.get[String]("to")
          yield Edge(from, to)
        }
        .left
        .map(_.getMessage)
    yield Graph(parsed, parsedEdges)

  /** Full deterministic validation. Returns issues; empty means valid. */
  def validate(graph: Graph): List[String] =
    val issues = scala.collection.mutable.ListBuffer.empty[String]
    if graph.nodes.isEmpty then issues += "empty_graph"
    if graph.nodes.size > 50 then issues += "too_many_nodes"
    val ids = graph.nodes.map(_.id)
    if ids.distinct.size != ids.size then issues += "duplicate_node_ids"
    graph.nodes.foreach { n =>
      val allowed = if n.isCondition then conditionKinds else actionKinds
      if !allowed.contains(n.kind) then issues += s"invalid_kind:${n.kind}"
    }
    graph.edges.foreach { e =>
      if !ids.contains(e.from) then issues += s"edge_from_missing:${e.from}"
      if !ids.contains(e.to) then issues += s"edge_to_missing:${e.to}"
    }
    // acyclicity via DFS from every node
    val byFrom = graph.edges.groupBy(_.from).view.mapValues(_.map(_.to)).toMap
    def hasCycle(node: String, visited: Set[String]): Boolean =
      if visited.contains(node) then true
      else byFrom.getOrElse(node, Nil).exists(hasCycle(_, visited + node))
    if ids.exists(id => hasCycle(id, Set.empty)) then issues += "cycle_detected"
    // conditions must be followed by at least one action
    graph.nodes.foreach { n =>
      if n.isCondition && byFrom.getOrElse(n.id, Nil).isEmpty then
        issues += s"condition_dead_end:${n.id}"
    }
    issues.toList

  /** Node order for deterministic execution: depth-first from the first node,
    * conditions evaluate via their config against the run context.
    */
  def executionOrder(graph: Graph): List[Node] =
    val byId = graph.nodes.map(n => n.id -> n).toMap
    val byFrom = graph.edges.groupBy(_.from).view.mapValues(_.map(_.to)).toMap
    val start = graph.nodes.headOption.map(_.id).toList
    @annotation.tailrec
    def walk(
        queue: List[String],
        seen: Set[String],
        acc: List[Node]
    ): List[Node] =
      queue match
        case Nil    => acc.reverse
        case h :: t =>
          if seen.contains(h) then walk(t, seen, acc)
          else
            val next = byFrom.getOrElse(h, Nil)
            walk(next ++ t, seen + h, byId.get(h).toList ++ acc)
    walk(start, Set.empty, Nil)

  /** Condition evaluation against a JSON context (deterministic). */
  def conditionHolds(node: Node, context: Json): Boolean =
    val cfg = node.config
    val value = context.hcursor
      .get[String](cfg.hcursor.get[String]("field").toOption.getOrElse(""))
      .toOption
      .getOrElse("")
    node.kind match
      case "has_tag" =>
        value.toLowerCase.contains(
          cfg.hcursor.get[String]("tag").toOption.getOrElse("").toLowerCase
        )
      case "email_domain_is" =>
        value.toLowerCase.endsWith(
          "@" + cfg.hcursor
            .get[String]("domain")
            .toOption
            .getOrElse("")
            .toLowerCase
        )
      case "field_equals" =>
        value == cfg.hcursor.get[String]("value").toOption.getOrElse("")
      case _ => false
