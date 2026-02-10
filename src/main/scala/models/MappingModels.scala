package models

import zio.json.*

enum NodeType:
  case Program, Copybook, SharedService

object NodeType:
  private val known: Map[String, NodeType] = Map(
    "PROGRAM"       -> NodeType.Program,
    "COPYBOOK"      -> NodeType.Copybook,
    "SHAREDSERVICE" -> NodeType.SharedService,
    "SERVICE"       -> NodeType.SharedService,
  )

  given JsonCodec[NodeType] = JsonCodec[String].transform(
    value => known.getOrElse(normalize(value), NodeType.Program),
    _.toString,
  )

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

enum EdgeType:
  case Includes, Calls, Uses

object EdgeType:
  private val known: Map[String, EdgeType] = Map(
    "INCLUDES" -> EdgeType.Includes,
    "CALLS"    -> EdgeType.Calls,
    "USES"     -> EdgeType.Uses,
  )

  given JsonCodec[EdgeType] = JsonCodec[String].transform(
    value => known.getOrElse(normalize(value), EdgeType.Uses),
    _.toString,
  )

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

case class DependencyNode(
  id: String,
  name: String,
  nodeType: NodeType,
  complexity: Int,
) derives JsonCodec

case class DependencyEdge(
  from: String,
  to: String,
  edgeType: EdgeType,
) derives JsonCodec

case class DependencyGraph(
  nodes: List[DependencyNode],
  edges: List[DependencyEdge],
  serviceCandidates: List[String],
) derives JsonCodec

object DependencyGraph:
  def empty: DependencyGraph = DependencyGraph(List.empty, List.empty, List.empty)
