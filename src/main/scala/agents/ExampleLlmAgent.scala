package agents

import zio.*
import zio.json.*
import zio.stream.*

import core.{ FileService, Logger }
import llm4zio.core.{ LlmError, LlmService, Message, MessageRole }
import llm4zio.tools.JsonSchema
import models.*

/** Example agent demonstrating full llm4zio migration (Strategy 3)
  *
  * This agent shows how to:
  *   - Use LlmService directly (not AIService)
  *   - Handle LlmError types
  *   - Use executeStructured for type-safe responses
  *   - Access conversation history
  *   - Use streaming for real-time feedback
  *
  * Compare with existing agents (CobolAnalyzerAgent) that use AIService to see the differences and benefits of full
  * migration.
  */
trait ExampleLlmAgent:
  /** Analyze a COBOL file using llm4zio directly */
  def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]

  /** Stream analysis results for real-time feedback */
  def analyzeStreaming(file: CobolFile): ZStream[Any, AnalysisError, String]

  /** Multi-turn conversation analysis */
  def analyzeWithContext(file: CobolFile, previousAnalyses: List[CobolAnalysis]): ZIO[Any, AnalysisError, CobolAnalysis]

object ExampleLlmAgent:
  /** ZIO service accessor */
  def analyze(file: CobolFile): ZIO[ExampleLlmAgent, AnalysisError, CobolAnalysis] =
    ZIO.serviceWithZIO[ExampleLlmAgent](_.analyze(file))

  def analyzeStreaming(file: CobolFile): ZStream[ExampleLlmAgent, AnalysisError, String] =
    ZStream.serviceWithStream[ExampleLlmAgent](_.analyzeStreaming(file))

  def analyzeWithContext(
    file: CobolFile,
    previousAnalyses: List[CobolAnalysis],
  ): ZIO[ExampleLlmAgent, AnalysisError, CobolAnalysis] =
    ZIO.serviceWithZIO[ExampleLlmAgent](_.analyzeWithContext(file, previousAnalyses))

  /** Implementation using llm4zio.core.LlmService
    *
    * Key differences from old AIService approach:
    *   1. Depends on LlmService instead of AIService
    *   2. Error handling uses LlmError instead of AIError
    *   3. executeStructured returns parsed type directly (no separate parsing step)
    *   4. Access to streaming and conversation history features
    */
  val live: ZLayer[LlmService & FileService & MigrationConfig, Nothing, ExampleLlmAgent] =
    ZLayer.fromFunction {
      (
        llmService: LlmService, // Changed from AIService
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new ExampleLlmAgent {

          override def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              _       <- Logger.info(s"Analyzing ${file.name} using llm4zio")
              content <- fileService
                           .readFile(file.path)
                           .mapError(fe => AnalysisError.FileReadFailed(file.path, fe.message))

              // Build prompt
              prompt = buildPrompt(file, content)
              schema = buildJsonSchema()

              // Use LlmService.executeStructured - returns CobolAnalysis directly!
              // No need for separate ResponseParser.parse step
              analysis <- llmService
                            .executeStructured[CobolAnalysis](prompt, schema)
                            .mapError(convertError(file.name))
                            .map(_.copy(file = file)) // Ensure file metadata is correct

              _ <- Logger.info(
                     s"Analysis complete for ${file.name}: ${analysis.complexity.linesOfCode} LOC"
                   )
            yield analysis

          override def analyzeStreaming(file: CobolFile): ZStream[Any, AnalysisError, String] =
            ZStream.unwrap {
              for
                content <- fileService
                             .readFile(file.path)
                             .mapError(fe => AnalysisError.FileReadFailed(file.path, fe.message))
                prompt   = buildPrompt(file, content)
              yield llmService
                .executeStream(prompt)
                .map(_.delta) // Extract text delta from each chunk
                .mapError(convertError(file.name))
            }

          override def analyzeWithContext(
            file: CobolFile,
            previousAnalyses: List[CobolAnalysis],
          ): ZIO[Any, AnalysisError, CobolAnalysis] =
            for
              content <- fileService
                           .readFile(file.path)
                           .mapError(fe => AnalysisError.FileReadFailed(file.path, fe.message))

              // Build conversation history from previous analyses
              messages = buildConversationHistory(file, content, previousAnalyses)

              schema = buildJsonSchema()

              // Use executeWithHistory for multi-turn conversation
              // Note: executeWithHistory returns LlmResponse, not the parsed type
              response <- llmService
                            .executeWithHistory(messages)
                            .mapError(convertError(file.name))

              // Parse the response content manually
              analysis <- ZIO
                            .fromEither(response.content.fromJson[CobolAnalysis])
                            .mapError(err =>
                              AnalysisError.ParseFailed(file.name, s"Failed to parse: $err")
                            )
                            .map(_.copy(file = file))
            yield analysis

          // Private helper methods

          private def buildPrompt(file: CobolFile, content: String): String =
            s"""Analyze this COBOL program and extract its structure.

File: ${file.name}
Lines: ${file.lineCount}

Content:
$content

Provide a detailed analysis including:
- Division structure (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
- Variables and data structures
- Procedures and control flow
- Copybook dependencies
- Complexity metrics

Return the analysis as a JSON object matching the CobolAnalysis schema."""

          private def buildJsonSchema(): JsonSchema =
            // In a real implementation, this would be generated from CobolAnalysis schema
            import zio.json.ast.Json
            Json.Obj(
              "type"       -> Json.Str("object"),
              "properties" -> Json.Obj(
                "file"       -> Json.Obj("type" -> Json.Str("object")),
                "divisions"  -> Json.Obj("type" -> Json.Str("object")),
                "variables"  -> Json.Obj("type" -> Json.Str("array")),
                "procedures" -> Json.Obj("type" -> Json.Str("array")),
                "copybooks"  -> Json.Obj("type" -> Json.Str("array")),
                "complexity" -> Json.Obj("type" -> Json.Str("object")),
              ),
            )

          private def buildConversationHistory(
            file: CobolFile,
            content: String,
            previousAnalyses: List[CobolAnalysis],
          ): List[Message] =
            val systemMessage = Message(
              MessageRole.System,
              "You are an expert COBOL analyzer. Analyze programs and extract structure.",
            )

            val history = previousAnalyses.flatMap { analysis =>
              List(
                Message(
                  MessageRole.User,
                  s"Previous analysis of ${analysis.file.name}",
                ),
                Message(
                  MessageRole.Assistant,
                  s"Analyzed ${analysis.file.name}: ${analysis.complexity.linesOfCode} LOC, ${analysis.procedures.size} procedures",
                ),
              )
            }

            val currentRequest = Message(
              MessageRole.User,
              buildPrompt(file, content),
            )

            systemMessage :: history ::: List(currentRequest)

          /** Convert LlmError to AnalysisError
            *
            * This shows how to handle the new error types from llm4zio. Each LlmError variant maps to an appropriate
            * AnalysisError.
            */
          private def convertError(fileName: String)(error: LlmError): AnalysisError =
            error match
              case LlmError.ProviderError(message, cause) =>
                AnalysisError.AIFailed(
                  fileName,
                  s"Provider error: $message${cause.map(c => s" (${c.getMessage})").getOrElse("")}",
                )

              case LlmError.RateLimitError(retryAfter) =>
                AnalysisError.AIFailed(
                  fileName,
                  s"Rate limited${retryAfter.map(d => s", retry after ${d.toSeconds}s").getOrElse("")}",
                )

              case LlmError.AuthenticationError(message) =>
                AnalysisError.AIFailed(fileName, s"Authentication failed: $message")

              case LlmError.InvalidRequestError(message) =>
                AnalysisError.AIFailed(fileName, s"Invalid request: $message")

              case LlmError.TimeoutError(duration) =>
                AnalysisError.AIFailed(fileName, s"Request timed out after ${duration.toSeconds}s")

              case LlmError.ParseError(message, raw) =>
                AnalysisError.ParseFailed(fileName, s"$message\nRaw: ${raw.take(200)}")

              case LlmError.ToolError(toolName, message) =>
                AnalysisError.AIFailed(fileName, s"Tool error ($toolName): $message")

              case LlmError.ConfigError(message) =>
                AnalysisError.AIFailed(fileName, s"Configuration error: $message")
        }
    }

  /** Example of using this agent in tests */
  object Example:
    /** Basic usage example */
    def basicAnalysis(file: CobolFile): ZIO[ExampleLlmAgent, AnalysisError, Unit] =
      for
        analysis <- ExampleLlmAgent.analyze(file)
        _        <- Logger.info(s"Analyzed ${analysis.file.name}")
        _        <- Logger.info(s"LOC: ${analysis.complexity.linesOfCode}")
        _        <- Logger.info(s"Procedures: ${analysis.procedures.size}")
      yield ()

    /** Streaming analysis example */
    def streamingAnalysis(file: CobolFile): ZIO[ExampleLlmAgent, AnalysisError, Unit] =
      ExampleLlmAgent
        .analyzeStreaming(file)
        .tap(chunk => Logger.info(s"Received: $chunk"))
        .runDrain

    /** Multi-turn conversation example */
    def contextualAnalysis(
      files: List[CobolFile]
    ): ZIO[ExampleLlmAgent, AnalysisError, List[CobolAnalysis]] =
      ZIO.foldLeft(files)(List.empty[CobolAnalysis]) { (analyses, file) =>
        ExampleLlmAgent
          .analyzeWithContext(file, analyses)
          .map(analysis => analyses :+ analysis)
      }

/** Comparison with old AIService approach:
  *
  * OLD (AIService):
  * ```scala
  * val live: ZLayer[AIService & ResponseParser & FileService, Nothing, Agent] =
  *   ZLayer.fromFunction { (aiService: AIService, parser: ResponseParser, ...) =>
  *     for
  *       response <- aiService.executeStructured(prompt, schema)
  *                     .mapError(e => AnalysisError.AIFailed(file.name, e.message))
  *       parsed   <- parser.parse[CobolAnalysis](response)
  *                     .mapError(e => AnalysisError.ParseFailed(file.name, e.message))
  *     yield parsed
  *   }
  * ```
  *
  * NEW (LlmService):
  * ```scala
  * val live: ZLayer[LlmService & FileService, Nothing, Agent] =
  *   ZLayer.fromFunction { (llmService: LlmService, ...) =>
  *     for
  *       analysis <- llmService.executeStructured[CobolAnalysis](prompt, schema)
  *                     .mapError(convertError(file.name))
  *     yield analysis
  *     // No separate parsing step - already typed!
  *   }
  * ```
  *
  * Benefits:
  *   - No ResponseParser dependency (one less layer)
  *   - Type-safe responses (executeStructured returns T directly)
  *   - Better error types (LlmError with specific variants)
  *   - Access to new features (streaming, tools, metrics)
  *   - Cleaner error handling
  */
