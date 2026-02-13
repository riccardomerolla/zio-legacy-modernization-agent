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

object CobolAnalyzerAgentSpec extends ZIOSpecDefault:

  class MockLlmService(
    structuredResponse: CobolAnalysis,
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
      execute(prompt = "history")

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      executeStream("history prompt")

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.ToolError("mock-tool", "Not implemented in mock"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(!shouldFail)

  private val sampleAnalysis: CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = Path.of("/tmp/PLACEHOLDER.cbl"),
        name = "PLACEHOLDER.cbl",
        size = 10,
        lineCount = 2,
        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some("PROGRAM-ID. PLACEHOLDER."),
        environment = None,
        data = Some("WORKING-STORAGE SECTION."),
        procedure = Some("PROCEDURE DIVISION."),
      ),
      variables = List(
        Variable(name = "WS-ID", level = 1, dataType = "NUMERIC", picture = Some("9(5)"), usage = None)
      ),
      procedures = List(
        Procedure(
          name = "MAIN",
          paragraphs = List("MAIN"),
          statements = List(Statement(lineNumber = 1, statementType = "STOP", content = "STOP RUN")),
        )
      ),
      copybooks = List("COPY1"),
      complexity = ComplexityMetrics(cyclomaticComplexity = 1, linesOfCode = 2, numberOfProcedures = 1),
    )

  def spec: Spec[Any, Any] = suite("CobolAnalyzerAgentSpec")(
    test("analyze returns parsed analysis and writes report") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-spec"))
          cobol     = tempDir.resolve("PROG1.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "PROG1.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(sampleAnalysis)),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
          report   <- readReport("PROG1.cbl")
          parsed    = report.fromJson[CobolAnalysis]
        yield assertTrue(
          analysis.file.name == "PROG1.cbl",
          analysis.complexity.linesOfCode == 2,
          parsed.isRight,
        )
      }
    },
    test("analyzeAll writes summary report") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-spec-all"))
          cobol1   = tempDir.resolve("PROG1.cbl")
          cobol2   = tempDir.resolve("PROG2.cbl")
          _       <- writeFile(cobol1, "IDENTIFICATION.\nPROCEDURE.\n")
          _       <- writeFile(cobol2, "IDENTIFICATION.\nPROCEDURE.\n")
          files    = List(
                       CobolFile(
                         path = cobol1,
                         name = "PROG1.cbl",
                         size = 10,
                         lineCount = 2,
                         lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                         encoding = "UTF-8",
                         fileType = FileType.Program,
                       ),
                       CobolFile(
                         path = cobol2,
                         name = "PROG2.cbl",
                         size = 10,
                         lineCount = 2,
                         lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                         encoding = "UTF-8",
                         fileType = FileType.Program,
                       ),
                     )
          _       <- CobolAnalyzerAgent
                       .analyzeAll(files)
                       .runCollect
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(sampleAnalysis)),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir, parallelism = 2)),
                         CobolAnalyzerAgent.live,
                       )
          summary <- readSummary()
          parsed   = summary.fromJson[List[CobolAnalysis]]
        yield assertTrue(parsed.exists(_.length == 2))
      }
    },
    test("analyze retries on truncated JSON and succeeds on second attempt") {
      ZIO.scoped {
        for
          tempDir    <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-retry"))
          cobol       = tempDir.resolve("PROG1.cbl")
          _          <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file        = CobolFile(
                          path = cobol,
                          name = "PROG1.cbl",
                          size = 10,
                          lineCount = 2,
                          lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                          encoding = "UTF-8",
                          fileType = FileType.Program,
                        )
          attemptRef <- Ref.make(0)
          llmLayer    = ZLayer.fromZIO {
                          ZIO.succeed(new LlmService {
                            override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema)
                              : IO[LlmError, A] =
                              attemptRef.updateAndGet(_ + 1).flatMap { count =>
                                if count <= 1 then
                                  ZIO.fail(LlmError.ParseError("Truncated JSON", "invalid json"))
                                else
                                  ZIO.succeed(sampleAnalysis.asInstanceOf[A])
                              }

                            override def execute(prompt: String): IO[LlmError, LlmResponse] =
                              ZIO.succeed(LlmResponse(content = "Mock", usage = None, metadata = Map.empty))

                            override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
                              ZStream.fail(LlmError.ProviderError("Not implemented", None))

                            override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
                              execute("history")

                            override def executeStreamWithHistory(messages: List[Message])
                              : ZStream[Any, LlmError, LlmChunk] =
                              executeStream("history")

                            override def executeWithTools(prompt: String, tools: List[AnyTool])
                              : IO[LlmError, ToolCallResponse] =
                              ZIO.fail(LlmError.ToolError("mock", "Not implemented"))

                            override def isAvailable: UIO[Boolean] =
                              ZIO.succeed(true)
                          })
                        }
          analysis   <- CobolAnalyzerAgent
                          .analyze(file)
                          .provide(
                            FileService.live,
                            llmLayer,
                            ZLayer.succeed(MigrationConfig(
                              sourceDir = tempDir,
                              outputDir = tempDir,
                              maxCompileRetries = 2,
                            )),
                            CobolAnalyzerAgent.live,
                          )
          attempts   <- attemptRef.get
        yield assertTrue(
          analysis.file.name == "PROG1.cbl",
          attempts == 2,
        )
      }
    },
    test("analyze normalizes divisions returned as array into object") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-div-array"))
          cobol     = tempDir.resolve("ARRAYPROG.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "ARRAYPROG.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(
                            CobolAnalysis(
                              file = CobolFile(
                                path = Path.of("/tmp/PLACEHOLDER.cbl"),
                                name = "PLACEHOLDER.cbl",
                                size = 10,
                                lineCount = 2,
                                lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                                encoding = "UTF-8",
                                fileType = FileType.Program,
                              ),
                              divisions = CobolDivisions(
                                identification = Some("PROGRAM-ID. ARRAYPROG."),
                                environment = Some("INPUT-OUTPUT SECTION."),
                                data = Some("WORKING-STORAGE SECTION."),
                                procedure = Some("MAIN-PARA. STOP RUN."),
                              ),
                              variables = List.empty,
                              procedures = List.empty,
                              copybooks = List.empty,
                              complexity =
                                ComplexityMetrics(cyclomaticComplexity = 1, linesOfCode = 5, numberOfProcedures = 1),
                            )
                          )),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.divisions.identification.contains("PROGRAM-ID. ARRAYPROG."),
          analysis.divisions.environment.contains("INPUT-OUTPUT SECTION."),
          analysis.divisions.data.contains("WORKING-STORAGE SECTION."),
          analysis.divisions.procedure.contains("MAIN-PARA. STOP RUN."),
        )
      }
    },
    test("analyze normalizes variables with missing required fields") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-missing-fields"))
          cobol     = tempDir.resolve("MISSING.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "MISSING.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(
                            CobolAnalysis(
                              file = CobolFile(
                                path = Path.of("/tmp/PLACEHOLDER.cbl"),
                                name = "PLACEHOLDER.cbl",
                                size = 10,
                                lineCount = 2,
                                lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                                encoding = "UTF-8",
                                fileType = FileType.Program,
                              ),
                              divisions = CobolDivisions(
                                identification = Some("PROGRAM-ID. MISSING."),
                                environment = None,
                                data = None,
                                procedure = None,
                              ),
                              variables = List(
                                Variable(
                                  name = "WS-AMOUNT",
                                  level = 1,
                                  dataType = "numeric",
                                  picture = Some("9(7)V99"),
                                  usage = None,
                                )
                              ),
                              procedures = List(
                                Procedure(
                                  name = "MAIN",
                                  paragraphs = List("MAIN"),
                                  statements =
                                    List(Statement(lineNumber = 0, statementType = "STOP", content = "STOP RUN")),
                                )
                              ),
                              copybooks = List.empty,
                              complexity =
                                ComplexityMetrics(cyclomaticComplexity = 1, linesOfCode = 5, numberOfProcedures = 1),
                            )
                          )),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.variables.head.name == "WS-AMOUNT",
          analysis.variables.head.level == 1,
          analysis.procedures.head.paragraphs == List("MAIN"),
          analysis.procedures.head.statements.head.lineNumber == 0,
        )
      }
    },
    test("analyze injects file when AI omits it entirely") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-no-file"))
          cobol     = tempDir.resolve("NOFILE.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "NOFILE.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(
                            CobolAnalysis(
                              file = CobolFile(
                                path = Path.of("/tmp/NOFILE.cbl"),
                                name = "NOFILE.cbl",
                                size = 10,
                                lineCount = 2,
                                lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                                encoding = "UTF-8",
                                fileType = FileType.Program,
                              ),
                              divisions = CobolDivisions(
                                identification = Some("PROGRAM-ID. NOFILE."),
                                environment = None,
                                data = None,
                                procedure = None,
                              ),
                              variables = List.empty,
                              procedures = List.empty,
                              copybooks = List.empty,
                              complexity =
                                ComplexityMetrics(cyclomaticComplexity = 1, linesOfCode = 3, numberOfProcedures = 1),
                            )
                          )),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.file.name == "NOFILE.cbl",
          analysis.complexity.linesOfCode == 3,
        )
      }
    },
    test("analyze filters out empty UNKNOWN statements from procedures") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-garbage-stmt"))
          cobol     = tempDir.resolve("GARBAGE.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "GARBAGE.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(
                            CobolAnalysis(
                              file = CobolFile(
                                path = Path.of("/tmp/GARBAGE.cbl"),
                                name = "GARBAGE.cbl",
                                size = 10,
                                lineCount = 2,
                                lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                                encoding = "UTF-8",
                                fileType = FileType.Program,
                              ),
                              divisions = CobolDivisions(
                                identification = Some("PROGRAM-ID. GARBAGE."),
                                environment = None,
                                data = None,
                                procedure = None,
                              ),
                              variables = List.empty,
                              procedures = List(
                                Procedure(
                                  name = "MAIN",
                                  paragraphs = List("MAIN"),
                                  statements = List(
                                    Statement(lineNumber = 1, statementType = "MOVE", content = "MOVE 1 TO X"),
                                    Statement(lineNumber = 5, statementType = "IF", content = "IF X > 0"),
                                  ),
                                )
                              ),
                              copybooks = List.empty,
                              complexity =
                                ComplexityMetrics(cyclomaticComplexity = 2, linesOfCode = 10, numberOfProcedures = 1),
                            )
                          )),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.procedures.head.statements.length == 2,
          analysis.procedures.head.statements(0).statementType == "MOVE",
          analysis.procedures.head.statements(1).statementType == "IF",
        )
      }
    },
    test("analyze normalizes copybooks returned as objects into strings") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-copybook-obj"))
          cobol     = tempDir.resolve("COPYBK.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "COPYBK.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ZLayer.succeed(new MockLlmService(
                            CobolAnalysis(
                              file = CobolFile(
                                path = Path.of("/tmp/COPYBK.cbl"),
                                name = "COPYBK.cbl",
                                size = 10,
                                lineCount = 2,
                                lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                                encoding = "UTF-8",
                                fileType = FileType.Program,
                              ),
                              divisions = CobolDivisions(
                                identification = Some("PROGRAM-ID. COPYBK."),
                                environment = None,
                                data = None,
                                procedure = None,
                              ),
                              variables = List.empty,
                              procedures = List.empty,
                              copybooks = List("ZBNKSET", "ERRCODE", "PLAIN"),
                              complexity =
                                ComplexityMetrics(cyclomaticComplexity = 1, linesOfCode = 5, numberOfProcedures = 1),
                            )
                          )),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.copybooks == List("ZBNKSET", "ERRCODE", "PLAIN")
        )
      }
    },
  ) @@ TestAspect.sequential

  private def writeFile(path: Path, content: String): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    }.unit

  private def readReport(name: String): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/analysis").resolve(s"$name.json"))
    }

  private def readSummary(): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/analysis/analysis-summary.json"))
    }
