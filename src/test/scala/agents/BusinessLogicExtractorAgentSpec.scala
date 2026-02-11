package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import core.{ AIService, FileService, ResponseParser }
import models.*

object BusinessLogicExtractorAgentSpec extends ZIOSpecDefault:

  private val sampleExtractionJson: String =
    """{
      |  "fileName": "PLACEHOLDER.cbl",
      |  "businessPurpose": "Manages customer lookup requests.",
      |  "useCases": [
      |    {
      |      "name": "Lookup customer",
      |      "trigger": "Inquiry request",
      |      "description": "Fetches customer details by identifier.",
      |      "keySteps": ["Validate request", "Read record", "Return result"]
      |    }
      |  ],
      |  "rules": [
      |    {
      |      "category": "DataValidation",
      |      "description": "Customer id is required.",
      |      "condition": "Before read",
      |      "errorCode": "CUST-001",
      |      "suggestion": "Provide customer id"
      |    }
      |  ],
      |  "summary": "Customer inquiry flow with input validation."
      |}""".stripMargin

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
                            ResponseParser.live,
                            mockAIService(sampleExtractionJson),
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
                         ResponseParser.live,
                         mockAIService(sampleExtractionJson),
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

  private def mockAIService(json: String): ULayer[AIService] =
    ZLayer.succeed(new AIService {
      override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
        ZIO.succeed(AIResponse(json))

      override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
        execute(prompt)

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(true)
    })
