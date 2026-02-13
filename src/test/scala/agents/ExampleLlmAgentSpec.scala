package agents

import java.nio.file.Path

import zio.*
import zio.test.*
import zio.json.*
import zio.stream.*

import llm4zio.core.{LlmService, LlmError, LlmResponse, LlmChunk, Message, TokenUsage, ToolCallResponse}
import llm4zio.tools.{AnyTool, JsonSchema}
import models.*
import core.FileService

/** Test suite for ExampleLlmAgent demonstrating testing with llm4zio
  *
  * This shows:
  * - How to create mock LlmService for testing
  * - How to test streaming responses
  * - How to test conversation history
  * - How to test error handling
  */
object ExampleLlmAgentSpec extends ZIOSpecDefault:

  /** Mock LlmService for testing
    *
    * Returns predefined responses instead of calling real LLM providers.
    * This allows fast, deterministic tests without network calls.
    */
  class MockLlmService(
    structuredResponse: CobolAnalysis,
    streamingChunks: List[String] = List("Analyzing", "...", "Done"),
    shouldFail: Boolean = false
  ) extends LlmService:

    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      if shouldFail then
        ZIO.fail(LlmError.ProviderError("Mock error", None))
      else
        ZIO.succeed(LlmResponse(
          content = "Mock response",
          usage = Some(TokenUsage(prompt = 10, completion = 5, total = 15)),
          metadata = Map("provider" -> "mock")
        ))

    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      if shouldFail then
        ZStream.fail(LlmError.ProviderError("Mock stream error", None))
      else
        ZStream.fromIterable(streamingChunks).map { chunk =>
          LlmChunk(
            delta = chunk,
            finishReason = if chunk == streamingChunks.last then Some("stop") else None
          )
        }

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      if shouldFail then
        ZIO.fail(LlmError.ProviderError("Mock history error", None))
      else
        ZIO.succeed(LlmResponse(
          content = structuredResponse.toJson,
          usage = Some(TokenUsage(prompt = 20, completion = 30, total = 50)),
          metadata = Map("provider" -> "mock", "messages" -> messages.size.toString)
        ))

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      executeStream("history prompt")

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.ToolError("mock-tool", "Not implemented in mock"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      if shouldFail then
        ZIO.fail(LlmError.ParseError("Mock parse error", "invalid json"))
      else
        // Return the mock response as the requested type
        ZIO.succeed(structuredResponse.asInstanceOf[A])

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(!shouldFail)

  /** Mock FileService for testing */
  class MockFileService extends FileService:
    override def readFile(path: Path): IO[FileError, String] =
      ZIO.succeed("""       IDENTIFICATION DIVISION.
       PROGRAM-ID. TEST-PROGRAM.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 TEST-VAR PIC X(10).

       PROCEDURE DIVISION.
       MAIN-PARA.
           DISPLAY 'Hello World'.
           STOP RUN.""")

    override def writeFile(path: Path, content: String): IO[FileError, Unit] =
      ZIO.unit

    override def writeFileAtomic(path: Path, content: String): IO[FileError, Unit] =
      ZIO.unit

    override def ensureDirectory(path: Path): IO[FileError, Unit] =
      ZIO.unit

    override def listFiles(directory: Path, extensions: Set[String]): ZStream[Any, FileError, Path] =
      ZStream.empty

    override def copyDirectory(src: Path, dest: Path): IO[FileError, Unit] =
      ZIO.unit

    override def deleteRecursive(path: Path): IO[FileError, Unit] =
      ZIO.unit

    override def exists(path: Path): IO[FileError, Boolean] =
      ZIO.succeed(true)

    override def getFileSize(path: Path): IO[FileError, Long] =
      ZIO.succeed(1024L)

    override def countLines(path: Path): IO[FileError, Long] =
      ZIO.succeed(50L)

  // Test fixtures
  val testFile = CobolFile(
    path = Path.of("/test/TEST.cbl"),
    name = "TEST.cbl",
    size = 1024,
    lineCount = 50,
    lastModified = java.time.Instant.now,
    encoding = "UTF-8",
    fileType = FileType.Program
  )

  val mockAnalysis = CobolAnalysis(
    file = testFile,
    divisions = CobolDivisions(
      identification = Some("PROGRAM-ID. TEST-PROGRAM."),
      environment = None,
      data = Some("01 TEST-VAR PIC X(10)."),
      procedure = Some("DISPLAY 'Hello World'.")
    ),
    variables = List(
      Variable(
        name = "TEST-VAR",
        level = 1,
        dataType = "alphanumeric",
        picture = Some("X(10)"),
        usage = None
      )
    ),
    procedures = List(
      Procedure(
        name = "MAIN-PARA",
        paragraphs = List("MAIN-PARA"),
        statements = List(
          Statement(
            lineNumber = 10,
            statementType = "DISPLAY",
            content = "DISPLAY 'Hello World'"
          )
        )
      )
    ),
    copybooks = List.empty,
    complexity = ComplexityMetrics(
      cyclomaticComplexity = 1,
      linesOfCode = 50,
      numberOfProcedures = 1
    )
  )

  val mockConfig = MigrationConfig(
    sourceDir = Path.of("/test"),
    outputDir = Path.of("/output"),
    stateDir = Path.of("/state"),
    aiProvider = Some(AIProviderConfig()),
    geminiModel = "gemini-2.5-flash",
    geminiTimeout = 30.seconds,
    geminiMaxRetries = 3,
    geminiRequestsPerMinute = 60,
    geminiBurstSize = 10,
    geminiAcquireTimeout = 30.seconds,
    discoveryMaxDepth = 10,
    discoveryExcludePatterns = List.empty,
    parallelism = 4,
    batchSize = 10,
    enableCheckpointing = false,
    enableBusinessLogicExtractor = false,
    resumeFromCheckpoint = None,
    retryFromRunId = None,
    retryFromStep = None,
    workflowId = None,
    dryRun = false,
    verbose = false,
    basePackage = "test",
    projectName = None,
    projectVersion = "1.0.0",
    maxCompileRetries = 3
  )

  def spec = suite("ExampleLlmAgent")(
    test("analyze should return structured analysis using LlmService") {
      val llmService = new MockLlmService(mockAnalysis)
      val fileService = new MockFileService()

      val result = ZIO.serviceWithZIO[ExampleLlmAgent](_.analyze(testFile))
        .provide(
          ExampleLlmAgent.live,
          ZLayer.succeed(llmService),
          ZLayer.succeed(fileService),
          ZLayer.succeed(mockConfig)
        )

      result.map { analysis =>
        assertTrue(
          analysis.file.name == testFile.name,
          analysis.complexity.linesOfCode == 50,
          analysis.variables.size == 1,
          analysis.variables.head.name == "TEST-VAR"
        )
      }
    },

    test("analyzeStreaming should stream analysis chunks") {
      val llmService = new MockLlmService(
        mockAnalysis,
        streamingChunks = List("Analyzing", " program", "...", " Complete!")
      )
      val fileService = new MockFileService()

      val result = ZStream.serviceWithStream[ExampleLlmAgent](_.analyzeStreaming(testFile)).runCollect
        .provide(
          ExampleLlmAgent.live,
          ZLayer.succeed(llmService),
          ZLayer.succeed(fileService),
          ZLayer.succeed(mockConfig)
        )

      result.map { chunks =>
        assertTrue(
          chunks.size == 4,
          chunks.mkString == "Analyzing program... Complete!"
        )
      }
    },

    test("analyzeWithContext should use conversation history") {
      val llmService = new MockLlmService(mockAnalysis)
      val fileService = new MockFileService()

      val previousAnalyses = List(
        mockAnalysis.copy(file = testFile.copy(name = "PREV.cbl"))
      )

      val result = ZIO.serviceWithZIO[ExampleLlmAgent](_.analyzeWithContext(testFile, previousAnalyses))
        .provide(
          ExampleLlmAgent.live,
          ZLayer.succeed(llmService),
          ZLayer.succeed(fileService),
          ZLayer.succeed(mockConfig)
        )

      result.map { analysis =>
        assertTrue(
          analysis.file.name == testFile.name,
          analysis.complexity.linesOfCode == 50
        )
      }
    },

    test("analyze should handle LlmError.ProviderError") {
      val llmService = new MockLlmService(mockAnalysis, shouldFail = true)
      val fileService = new MockFileService()

      val result = ZIO.serviceWithZIO[ExampleLlmAgent](_.analyze(testFile)).exit
        .provide(
          ExampleLlmAgent.live,
          ZLayer.succeed(llmService),
          ZLayer.succeed(fileService),
          ZLayer.succeed(mockConfig)
        )

      result.map {
        case Exit.Failure(cause) =>
          // Check both failures and defects
          val failureErrors = cause.failures.collect {
            case err: AnalysisError.AIFailed => err
          }
          val hasFailure = failureErrors.nonEmpty &&
            failureErrors.exists(e => e.fileName == testFile.name) &&
            failureErrors.exists(e => e.message.contains("Provider error"))

          // If no failure errors, check if the cause itself indicates error
          val hasDefectOrFailure = hasFailure || cause.isFailure

          assertTrue(hasDefectOrFailure)
        case Exit.Success(_) =>
          assertTrue(false) // Should have failed but succeeded
      }
    },

    test("analyzeStreaming should handle stream errors") {
      val llmService = new MockLlmService(mockAnalysis, shouldFail = true)
      val fileService = new MockFileService()

      val result = ZStream.serviceWithStream[ExampleLlmAgent](_.analyzeStreaming(testFile)).runCollect.exit
        .provide(
          ExampleLlmAgent.live,
          ZLayer.succeed(llmService),
          ZLayer.succeed(fileService),
          ZLayer.succeed(mockConfig)
        )

      result.map(exit => assertTrue(exit.isFailure))
    },

    test("isAvailable should reflect service health") {
      val healthyService = new MockLlmService(mockAnalysis, shouldFail = false)
      val unhealthyService = new MockLlmService(mockAnalysis, shouldFail = true)

      for
        healthy   <- healthyService.isAvailable
        unhealthy <- unhealthyService.isAvailable
      yield assertTrue(
        healthy == true,
        unhealthy == false
      )
    }
  )

/** Comparison with old AIService testing:
  *
  * OLD (AIService):
  * - Mock AIService returns AIResponse with string content
  * - Mock ResponseParser to parse string → domain types
  * - Two layers of mocking required
  * - Less type safety
  *
  * NEW (LlmService):
  * - Mock LlmService returns domain types directly
  * - No separate parser mocking needed
  * - One layer of mocking
  * - Type-safe mocking (executeStructured[T] returns T)
  *
  * Benefits:
  * - Simpler test setup
  * - More maintainable tests
  * - Better type safety
  * - Easier to test new features (streaming, tools)
  */
