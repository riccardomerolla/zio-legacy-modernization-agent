package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import core.FileService
import llm4zio.core.{ LlmService, LlmError, LlmResponse, LlmChunk, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import models.*

object ValidationAgentSpec extends ZIOSpecDefault:

  class MockLlmService(
    structuredResponse: SemanticValidation,
    shouldFail: Boolean = false
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

  private val validSemanticValidation: SemanticValidation =
    SemanticValidation(
      businessLogicPreserved = true,
      confidence = 0.91,
      summary = "Equivalent mapping",
      issues = List.empty
    )

  private val invalidSemanticValidation: SemanticValidation =
    SemanticValidation(
      businessLogicPreserved = false,
      confidence = 0.0,
      summary = "Semantic validation failed: unparsable response from LLM",
      issues = List(
        ValidationIssue(
          severity = Severity.ERROR,
          category = IssueCategory.Semantic,
          message = "Unable to parse semantic validation response",
          file = None,
          line = None,
          suggestion = None
        )
      )
    )

  def spec: Spec[Any, Any] = suite("ValidationAgentSpec")(
    test("validate generates report and writes files") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("validation-spec"))
          project  = sampleProject("VALPROG")
          analysis = sampleAnalysis("VALPROG.cbl")
          report  <- ValidationAgent
                       .validate(project, analysis)
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(validSemanticValidation)),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                         ValidationAgent.live,
                       )
          jsonPath = Path.of("reports/validation/valprog-validation.json")
          mdPath   = Path.of("reports/validation/valprog-validation.md")
          jsonOk  <- ZIO.attemptBlocking(Files.exists(jsonPath))
          mdOk    <- ZIO.attemptBlocking(Files.exists(mdPath))
          json    <- ZIO.attemptBlocking(Files.readString(jsonPath))
          parsed   = json.fromJson[ValidationReport]
        yield assertTrue(
          report.projectName == "VALPROG",
          report.compileResult.success == false,
          report.issues.exists(_.category == IssueCategory.Compile),
          report.overallStatus == ValidationStatus.Failed,
          jsonOk,
          mdOk,
          parsed.isRight,
        )
      }
    },
    test("validate uses fallback semantic report when semantic response is invalid") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("validation-spec-semantic"))
          project  = sampleProject("BADSEM")
          analysis = sampleAnalysis("BADSEM.cbl")
          result  <- ValidationAgent
                       .validate(project, analysis)
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(invalidSemanticValidation)),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                         ValidationAgent.live,
                       )
        yield assertTrue(
          result.projectName == "BADSEM",
          !result.semanticValidation.businessLogicPreserved,
          result.semanticValidation.issues.exists(_.category == IssueCategory.Semantic),
          result.semanticValidation.summary.contains("unparsable response"),
        )
      }
    },
  ) @@ TestAspect.sequential

  private def sampleAnalysis(name: String): CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = Path.of(s"/tmp/$name"),
        name = name,
        size = 10,
        lineCount = 2,
        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some("PROGRAM-ID. SAMPLE."),
        environment = None,
        data = Some("01 WS-ID PIC 9(5)."),
        procedure = Some("MAIN. MOVE 1 TO WS-ID."),
      ),
      variables = List(
        Variable("WS-ID", 1, "NUMERIC", Some("9(5)"), None),
        Variable("WS-UNMAPPED", 1, "NUMERIC", Some("9(3)"), None),
      ),
      procedures = List(
        Procedure("MAIN", List("MAIN"), List(Statement(1, "MOVE", "MOVE 1 TO WS-ID"))),
        Procedure("UNMAPPED-PROC", List("UNMAPPED-PROC"), List.empty),
      ),
      copybooks = List("COPY1"),
      complexity = ComplexityMetrics(3, 20, 2),
    )

  private def sampleProject(name: String): SpringBootProject =
    SpringBootProject(
      projectName = name,
      sourceProgram = s"$name.cbl",
      generatedAt = java.time.Instant.parse("2026-02-06T00:00:00Z"),
      entities = List(
        JavaEntity(
          className = "Record",
          packageName = "com.example.record.entity",
          fields = List(JavaField("wsId", "Long", "WS-ID", List("@Column"))),
          annotations = List("@Entity"),
          sourceCode = "public class Record {}",
        )
      ),
      services = List(
        JavaService(
          name = "RecordService",
          methods = List(JavaMethod("main", "void", List.empty, "")),
        )
      ),
      controllers = List(
        JavaController(
          name = "RecordController",
          basePath = "/api/records",
          endpoints = List(RestEndpoint("/run", HttpMethod.POST, "run")),
        )
      ),
      repositories = List(
        JavaRepository(
          name = "RecordRepository",
          entityName = "Record",
          idType = "Long",
          packageName = "com.example.record.repository",
          annotations = List("@Repository"),
          sourceCode = "public interface RecordRepository {}",
        )
      ),
      configuration = ProjectConfiguration(
        groupId = "com.example",
        artifactId = name.toLowerCase,
        dependencies = List("spring-boot-starter-web"),
      ),
      buildFile = BuildFile("maven", "<project/>"),
    )
