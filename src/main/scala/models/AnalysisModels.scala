package models

import java.nio.file.Paths
import java.time.Instant

import zio.json.*

case class CobolDivisions(
  identification: Option[String],
  environment: Option[String],
  data: Option[String],
  procedure: Option[String],
) derives JsonCodec

case class Variable(
  name: String,
  level: Int,
  dataType: String,
  picture: Option[String],
  usage: Option[String],
) derives JsonCodec

case class Statement(
  lineNumber: Int,
  statementType: String,
  content: String,
) derives JsonCodec

case class Procedure(
  name: String,
  paragraphs: List[String],
  statements: List[Statement],
) derives JsonCodec

case class ComplexityMetrics(
  cyclomaticComplexity: Int,
  linesOfCode: Int,
  numberOfProcedures: Int,
) derives JsonCodec

case class CobolAnalysis(
  file: CobolFile,
  divisions: CobolDivisions,
  variables: List[Variable],
  procedures: List[Procedure],
  copybooks: List[String],
  complexity: ComplexityMetrics,
) derives JsonCodec

object CobolAnalysis:
  def empty: CobolAnalysis = CobolAnalysis(
    file = CobolFile(
      path = Paths.get(""),
      name = "",
      size = 0L,
      lineCount = 0L,
      lastModified = Instant.EPOCH,
      encoding = "UTF-8",
      fileType = FileType.Program,
    ),
    divisions = CobolDivisions(None, None, None, None),
    variables = List.empty,
    procedures = List.empty,
    copybooks = List.empty,
    complexity = ComplexityMetrics(0, 0, 0),
  )

case class BusinessUseCase(
  name: String,
  trigger: String,
  description: String,
  keySteps: List[String],
) derives JsonCodec

case class BusinessRule(
  category: String,
  description: String,
  condition: Option[String],
  errorCode: Option[String],
  suggestion: Option[String],
) derives JsonCodec

case class BusinessLogicExtraction(
  fileName: String,
  businessPurpose: String,
  useCases: List[BusinessUseCase],
  rules: List[BusinessRule],
  summary: String,
) derives JsonCodec
