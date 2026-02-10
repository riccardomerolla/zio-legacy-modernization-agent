package models

import java.time.Instant

import zio.json.*
import zio.json.ast.Json

case class TestResults(
  totalTests: Int,
  passed: Int,
  failed: Int,
) derives JsonCodec

enum Severity:
  case ERROR, WARNING, INFO

object Severity:
  private val known: Map[String, Severity] = Map(
    "ERROR"   -> Severity.ERROR,
    "WARNING" -> Severity.WARNING,
    "WARN"    -> Severity.WARNING,
    "INFO"    -> Severity.INFO,
  )

  given JsonCodec[Severity] = JsonCodec[String].transform(
    value => known.getOrElse(normalize(value), Severity.WARNING),
    _.toString,
  )

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

enum IssueCategory:
  case Compile, Coverage, StaticAnalysis, Semantic, Convention, Undefined

object IssueCategory:
  private val known: Map[String, IssueCategory] = Map(
    "COMPILE"        -> IssueCategory.Compile,
    "COMPILATION"    -> IssueCategory.Compile,
    "COVERAGE"       -> IssueCategory.Coverage,
    "STATICANALYSIS" -> IssueCategory.StaticAnalysis,
    "STATICCHECK"    -> IssueCategory.StaticAnalysis,
    "CHECKSTYLE"     -> IssueCategory.StaticAnalysis,
    "SPOTBUGS"       -> IssueCategory.StaticAnalysis,
    "SEMANTIC"       -> IssueCategory.Semantic,
    "BUSINESSLOGIC"  -> IssueCategory.Semantic,
    "LOGIC"          -> IssueCategory.Semantic,
    "ARCHITECTURAL"  -> IssueCategory.Semantic,
    "ARCHITECTURE"   -> IssueCategory.Semantic,
    "DESIGN"         -> IssueCategory.Semantic,
    "CONVENTION"     -> IssueCategory.Convention,
    "STYLE"          -> IssueCategory.Convention,
    "FORMATTING"     -> IssueCategory.Convention,
    "UNDEFINED"      -> IssueCategory.Undefined,
  )

  given JsonCodec[IssueCategory] = JsonCodec[String].transform(
    value => fromRaw(value),
    category => category.toString,
  )

  def fromRaw(value: String): IssueCategory =
    known.getOrElse(normalize(value), IssueCategory.Undefined)

  def isKnownRaw(value: String): Boolean =
    known.contains(normalize(value))

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

case class CompileResult(
  success: Boolean,
  exitCode: Int,
  output: String,
) derives JsonCodec

case class ValidationIssue(
  severity: Severity,
  category: IssueCategory,
  message: String,
  file: Option[String],
  line: Option[Int],
  suggestion: Option[String],
)

object ValidationIssue:
  private case class RawValidationIssue(
    severity: Severity,
    category: IssueCategory,
    message: String,
    file: Option[String],
    line: Option[Json],
    suggestion: Option[String],
  ) derives JsonCodec

  given JsonCodec[ValidationIssue] = JsonCodec[RawValidationIssue].transformOrFail(
    raw =>
      decodeLine(raw.line).map(line =>
        ValidationIssue(
          severity = raw.severity,
          category = raw.category,
          message = raw.message,
          file = raw.file,
          line = line,
          suggestion = raw.suggestion,
        )
      ),
    issue =>
      RawValidationIssue(
        severity = issue.severity,
        category = issue.category,
        message = issue.message,
        file = issue.file,
        line = issue.line.map(v => Json.Num(v)),
        suggestion = issue.suggestion,
      ),
  )

  private def decodeLine(value: Option[Json]): Either[String, Option[Int]] =
    value match
      case None                   => Right(None)
      case Some(Json.Null)        => Right(None)
      case Some(Json.Num(number)) =>
        scala.util.Try(number.intValueExact()).toOption
          .map(Some(_))
          .toRight(s"unsupported numeric line value: $number")
      case Some(Json.Str(text))   =>
        val normalized = text.trim
        normalized.toIntOption
          .orElse(normalized.toDoubleOption.map(_.toInt))
          .map(Some(_))
          .toRight(s"unsupported string line value: $text")
      case Some(other)            => Left(s"unsupported line value type: $other")

case class CoverageMetrics(
  variablesCovered: Double,
  proceduresCovered: Double,
  fileSectionCovered: Double,
  unmappedItems: List[String],
) derives JsonCodec

case class SemanticValidation(
  businessLogicPreserved: Boolean,
  confidence: Double,
  summary: String,
  issues: List[ValidationIssue],
) derives JsonCodec

enum ValidationStatus:
  case Passed, PassedWithWarnings, Failed

object ValidationStatus:
  private val known: Map[String, ValidationStatus] = Map(
    "PASSED"             -> ValidationStatus.Passed,
    "PASSEDWITHWARNINGS" -> ValidationStatus.PassedWithWarnings,
    "FAILED"             -> ValidationStatus.Failed,
  )

  given JsonCodec[ValidationStatus] = JsonCodec[String].transform(
    value => known.getOrElse(normalize(value), ValidationStatus.Failed),
    _.toString,
  )

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

case class ValidationReport(
  projectName: String,
  validatedAt: Instant,
  compileResult: CompileResult,
  coverageMetrics: CoverageMetrics,
  issues: List[ValidationIssue],
  semanticValidation: SemanticValidation,
  overallStatus: ValidationStatus,
) derives JsonCodec

object ValidationReport:
  def empty: ValidationReport = ValidationReport(
    projectName = "",
    validatedAt = Instant.EPOCH,
    compileResult = CompileResult(success = false, exitCode = -1, output = ""),
    coverageMetrics = CoverageMetrics(0.0, 0.0, 0.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(
      businessLogicPreserved = false,
      confidence = 0.0,
      summary = "",
      issues = List.empty,
    ),
    overallStatus = ValidationStatus.Failed,
  )
