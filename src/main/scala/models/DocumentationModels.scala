package models

import java.time.Instant

import zio.json.*

enum DiagramType derives JsonCodec:
  case Mermaid, PlantUML

case class Diagram(
  name: String,
  diagramType: DiagramType,
  content: String,
) derives JsonCodec

case class MigrationDocumentation(
  generatedAt: Instant,
  summaryReport: String,
  designDocument: String,
  apiDocumentation: String,
  dataMappingReference: String,
  deploymentGuide: String,
  diagrams: List[Diagram],
) derives JsonCodec

object MigrationDocumentation:
  def empty: MigrationDocumentation = MigrationDocumentation(
    generatedAt = Instant.EPOCH,
    summaryReport = "",
    designDocument = "",
    apiDocumentation = "",
    dataMappingReference = "",
    deploymentGuide = "",
    diagrams = List.empty,
  )
