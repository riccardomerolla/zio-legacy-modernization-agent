package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import core.{ AIService, FileService, Logger, ResponseParser }
import models.*
import prompts.PromptTemplates

/** BusinessLogicExtractorAgent - Extract business purpose, use cases, and rules from COBOL analyses.
  */
trait BusinessLogicExtractorAgent:
  def extract(analysis: CobolAnalysis): ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction]
  def extractAll(analyses: List[CobolAnalysis]): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]]

object BusinessLogicExtractorAgent:
  def extract(analysis: CobolAnalysis)
    : ZIO[BusinessLogicExtractorAgent, BusinessLogicExtractionError, BusinessLogicExtraction] =
    ZIO.serviceWithZIO[BusinessLogicExtractorAgent](_.extract(analysis))

  def extractAll(analyses: List[CobolAnalysis])
    : ZIO[BusinessLogicExtractorAgent, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
    ZIO.serviceWithZIO[BusinessLogicExtractorAgent](_.extractAll(analyses))

  val live: ZLayer[AIService & ResponseParser & FileService & MigrationConfig, Nothing, BusinessLogicExtractorAgent] =
    ZLayer.fromFunction {
      (
        aiService: AIService,
        responseParser: ResponseParser,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new BusinessLogicExtractorAgent {
          private val reportDir = Path.of("reports/business-logic")

          override def extract(analysis: CobolAnalysis)
            : ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
            for
              _        <- Logger.info(s"Extracting business logic from ${analysis.file.name}")
              prompt    = PromptTemplates.BusinessLogicExtractor.extractBusinessLogic(analysis)
              response <- aiService
                            .execute(prompt)
                            .mapError(err => BusinessLogicExtractionError.AIFailed(analysis.file.name, err.message))
              parsed   <- responseParser
                            .parse[BusinessLogicExtraction](response)
                            .mapError(err => BusinessLogicExtractionError.ParseFailed(analysis.file.name, err.message))
              enriched  = parsed.copy(
                            fileName = if parsed.fileName.trim.nonEmpty then parsed.fileName else analysis.file.name
                          )
              _        <- writeReport(enriched).tapError(err => Logger.warn(err.message))
            yield enriched

          override def extractAll(
            analyses: List[CobolAnalysis]
          ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
            for
              results <- ZIO.foreachPar(analyses)(extract).withParallelism(config.parallelism)
              _       <- writeSummary(results).tapError(err => Logger.warn(err.message))
            yield results

          private def writeReport(extraction: BusinessLogicExtraction): ZIO[Any, BusinessLogicExtractionError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve(s"${safeName(extraction.fileName)}.json")
              content = extraction.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(path, fe.message))
            yield ()

          private def writeSummary(
            extractions: List[BusinessLogicExtraction]
          ): ZIO[Any, BusinessLogicExtractionError, Unit] =
            for
              _      <- fileService
                          .ensureDirectory(reportDir)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(reportDir, fe.message))
              path    = reportDir.resolve("business-logic-summary.json")
              content = extractions.toJsonPretty
              _      <- fileService
                          .writeFileAtomic(path, content)
                          .mapError(fe => BusinessLogicExtractionError.ReportWriteFailed(path, fe.message))
            yield ()

          private def safeName(fileName: String): String =
            fileName
              .replaceAll("[^A-Za-z0-9._-]", "_")
              .replaceAll("\\.+", ".")
        }
    }
