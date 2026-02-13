package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import core.FileService
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import models.*

object BusinessLogicExtractorAgentSpec extends ZIOSpecDefault:

  class MockLlmService(
    structuredResponse: BusinessLogicExtraction,
    shouldFail: Boolean = false,
  ) extends LlmService:
    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      if shouldFail then
        ZIO.fail(LlmError.ParseError("Mock parse error", "invalid json"))
      else
        ZIO.succeed(structuredResponse.asInstanceOf[A])

    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = "Mock", usage = None, metadata = Map.empty))

    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      ZStream.fail(LlmError.ProviderError("Not implemented in mock", None))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      execute("history")

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      executeStream("history")

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.ToolError("mock-tool", "Not implemented in mock"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(!shouldFail)

  private val sampleExtraction: BusinessLogicExtraction =
    BusinessLogicExtraction(
      fileName = "PLACEHOLDER.cbl",
      businessPurpose = "Manages customer lookup requests.",
      useCases = List(
        BusinessUseCase(
          name = "Lookup customer",
          trigger = "Inquiry request",
          description = "Fetches customer details by identifier.",
          keySteps = List("Validate request", "Read record", "Return result"),
        )
      ),
      rules = List(
        BusinessRule(
          category = "DataValidation",
          description = "Customer id is required.",
          condition = Some("Before read"),
          errorCode = Some("CUST-001"),
          suggestion = Some("Provide customer id"),
        )
      ),
      summary = "Customer inquiry flow with input validation.",
    )

  def spec: Spec[Any, Any] = suite("BusinessLogicExtractorAgentSpec")(
    test("extract parses JSON and writes report") {
      ZIO.scoped {
        for
          tempDir    <- ZIO.attemptBlocking(Files.createTempDirectory("business-logic-agent-spec"))
          analysis    = sampleAnalysis(tempDir)
          extraction <- BusinessLogicExtractorAgent
                          .extract(analysis)
                          .provide(
                            FileService.live,
                            ZLayer.succeed(new MockLlmService(sampleExtraction)),
                            ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                            BusinessLogicExtractorAgent.live,
                          )
          report     <- ZIO.attemptBlocking(Files.readString(Path.of("reports/business-logic/PLACEHOLDER.cbl.json")))
          parsed      = report.fromJson[BusinessLogicExtraction]
        yield assertTrue(
          extraction.fileName == "PLACEHOLDER.cbl",
          extraction.useCases.nonEmpty,
          parsed.isRight,
        )
      }
    },
    test("extractAll writes summary report") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("business-logic-agent-spec-all"))
          a1       = sampleAnalysis(tempDir).copy(file = sampleAnalysis(tempDir).file.copy(name = "PROG1.cbl"))
          a2       = sampleAnalysis(tempDir).copy(file = sampleAnalysis(tempDir).file.copy(name = "PROG2.cbl"))
          out     <- BusinessLogicExtractorAgent
                       .extractAll(List(a1, a2))
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(sampleExtraction)),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir, parallelism = 2)),
                         BusinessLogicExtractorAgent.live,
                       )
          summary <-
            ZIO.attemptBlocking(Files.readString(Path.of("reports/business-logic/business-logic-summary.json")))
          parsed   = summary.fromJson[List[BusinessLogicExtraction]]
        yield assertTrue(
          out.size == 2,
          parsed.exists(_.size == 2),
        )
      }
    },
  ) @@ TestAspect.sequential

  private def sampleAnalysis(tempDir: Path): CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = tempDir.resolve("SAMPLE.cbl"),
        name = "SAMPLE.cbl",
        size = 100,
        lineCount = 10,
        lastModified = java.time.Instant.parse("2026-02-10T00:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some("PROGRAM-ID. SAMPLE."),
        environment = None,
        data = Some("WORKING-STORAGE SECTION."),
        procedure = Some("PROCEDURE DIVISION. MAIN-PARA. STOP RUN."),
      ),
      variables = List(Variable("WS-ID", 1, "numeric", Some("9(5)"), None)),
      procedures = List(Procedure("MAIN-PARA", List("MAIN-PARA"), List(Statement(1, "STOP", "STOP RUN")))),
      copybooks = List("CUSTOMER-DATA"),
      complexity = ComplexityMetrics(1, 10, 1),
    )
