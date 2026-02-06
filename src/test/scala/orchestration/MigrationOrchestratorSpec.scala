package orchestration

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.*
import zio.stream.*
import zio.test.*

import agents.*
import core.*
import models.*

object MigrationOrchestratorSpec extends ZIOSpecDefault:

  // ============================================================================
  // Mock Implementations
  // ============================================================================

  private val sampleFile = CobolFile(
    path = Paths.get("/cobol/PROG1.cbl"),
    name = "PROG1.cbl",
    size = 1000L,
    lineCount = 100,
    lastModified = Instant.EPOCH,
    encoding = "UTF-8",
    fileType = FileType.Program,
  )

  private val sampleInventory = FileInventory(
    discoveredAt = Instant.EPOCH,
    sourceDirectory = Paths.get("/cobol"),
    files = List(sampleFile),
    summary = InventorySummary(1, 1, 0, 0, 100, 1000L),
  )

  private val sampleAnalysis = CobolAnalysis(
    file = sampleFile,
    divisions = CobolDivisions(Some("PROGRAM-ID. PROG1."), None, None, None),
    variables = List.empty,
    procedures = List.empty,
    copybooks = List.empty,
    complexity = ComplexityMetrics(1, 100, 1),
  )

  private val sampleGraph = DependencyGraph(
    nodes = List(DependencyNode("PROG1", "PROG1", NodeType.Program, 1)),
    edges = List.empty,
    serviceCandidates = List.empty,
  )

  private val sampleProject = SpringBootProject(
    projectName = "prog1-service",
    sourceProgram = "PROG1.cbl",
    generatedAt = Instant.EPOCH,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("com.example", "prog1", List.empty),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleValidation = ValidationReport(
    projectName = "prog1-service",
    validatedAt = Instant.EPOCH,
    compileResult = CompileResult(success = true, exitCode = 0, output = ""),
    coverageMetrics = CoverageMetrics(90.0, 85.0, 95.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(true, 0.95, "OK", List.empty),
    overallStatus = ValidationStatus.Passed,
  )

  private val sampleDocs = MigrationDocumentation(
    generatedAt = Instant.EPOCH,
    summaryReport = "# Summary",
    designDocument = "# Design",
    apiDocumentation = "# API",
    dataMappingReference = "# Data",
    deploymentGuide = "# Deploy",
    diagrams = List.empty,
  )

  private def mockDiscoveryAgent: ULayer[CobolDiscoveryAgent] =
    ZLayer.succeed(new CobolDiscoveryAgent:
      override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
        ZIO.succeed(sampleInventory))

  private def mockAnalyzerAgent: ULayer[CobolAnalyzerAgent] =
    ZLayer.succeed(new CobolAnalyzerAgent:
      override def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]               =
        ZIO.succeed(sampleAnalysis)
      override def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis] =
        ZStream.fromIterable(files.map(_ => sampleAnalysis)))

  private def mockMapperAgent: ULayer[DependencyMapperAgent] =
    ZLayer.succeed(new DependencyMapperAgent:
      override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
        ZIO.succeed(sampleGraph))

  private def mockTransformerAgent: ULayer[JavaTransformerAgent] =
    ZLayer.succeed(new JavaTransformerAgent:
      override def transform(
        analysis: CobolAnalysis,
        graph: DependencyGraph,
      ): ZIO[Any, TransformError, SpringBootProject] =
        ZIO.succeed(sampleProject))

  private def mockValidationAgent: ULayer[ValidationAgent] =
    ZLayer.succeed(new ValidationAgent:
      override def validate(
        project: SpringBootProject,
        analysis: CobolAnalysis,
      ): ZIO[Any, ValidationError, ValidationReport] =
        ZIO.succeed(sampleValidation))

  private def mockDocumentationAgent: ULayer[DocumentationAgent] =
    ZLayer.succeed(new DocumentationAgent:
      override def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation] =
        ZIO.succeed(sampleDocs))

  private def mockStateService: ULayer[StateService] =
    ZLayer.succeed(new StateService:
      override def saveState(state: MigrationState): ZIO[Any, StateError, Unit]                     = ZIO.unit
      override def loadState(runId: String): ZIO[Any, StateError, Option[MigrationState]]           = ZIO.succeed(None)
      override def createCheckpoint(runId: String, step: MigrationStep): ZIO[Any, StateError, Unit] = ZIO.unit
      override def getLastCheckpoint(runId: String): ZIO[Any, StateError, Option[MigrationStep]]    = ZIO.succeed(None)
      override def listRuns(): ZIO[Any, StateError, List[MigrationRunSummary]]                      = ZIO.succeed(List.empty))

  private def mockGeminiService: ULayer[GeminiService] =
    ZLayer.succeed(new GeminiService:
      override def execute(prompt: String): ZIO[Any, GeminiError, GeminiResponse]                             =
        ZIO.succeed(GeminiResponse("mock", 0))
      override def executeWithContext(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
        ZIO.succeed(GeminiResponse("mock", 0))
      override def isAvailable: ZIO[Any, Nothing, Boolean]                                                    = ZIO.succeed(true))

  private val allMockLayers =
    mockDiscoveryAgent ++
      mockAnalyzerAgent ++
      mockMapperAgent ++
      mockTransformerAgent ++
      mockValidationAgent ++
      mockDocumentationAgent ++
      mockStateService ++
      mockGeminiService

  def spec: Spec[Any, Any] = suite("MigrationOrchestratorSpec")(
    suite("runFullMigration")(
      test("completes full pipeline successfully") {
        for
          result <- MigrationOrchestrator
                      .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                      .provide(MigrationOrchestrator.live, allMockLayers)
        yield assertTrue(
          result.success,
          result.projects.length == 1,
          result.projects.head.projectName == "prog1-service",
          result.validationReports.length == 1,
          result.validationReports.head.overallStatus == ValidationStatus.Passed,
          result.documentation.summaryReport == "# Summary",
        )
      },
      test("propagates discovery errors") {
        val failingDiscovery = ZLayer.succeed(new CobolDiscoveryAgent:
          override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
            ZIO.fail(DiscoveryError.SourceNotFound(sourcePath)))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/missing"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          failingDiscovery,
                          mockAnalyzerAgent,
                          mockMapperAgent,
                          mockTransformerAgent,
                          mockValidationAgent,
                          mockDocumentationAgent,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
      test("propagates analysis errors") {
        val failingAnalyzer = ZLayer.succeed(new CobolAnalyzerAgent:
          override def analyze(file: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]               =
            ZIO.fail(AnalysisError.GeminiFailed(file.name, "timeout"))
          override def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis] =
            ZStream.fromZIO(ZIO.fail(AnalysisError.GeminiFailed("test", "timeout"))))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          mockDiscoveryAgent,
                          failingAnalyzer,
                          mockMapperAgent,
                          mockTransformerAgent,
                          mockValidationAgent,
                          mockDocumentationAgent,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
      test("propagates mapping errors") {
        val failingMapper = ZLayer.succeed(new DependencyMapperAgent:
          override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
            ZIO.fail(MappingError.EmptyAnalysis))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          mockDiscoveryAgent,
                          mockAnalyzerAgent,
                          failingMapper,
                          mockTransformerAgent,
                          mockValidationAgent,
                          mockDocumentationAgent,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
      test("propagates transform errors") {
        val failingTransformer = ZLayer.succeed(new JavaTransformerAgent:
          override def transform(
            analysis: CobolAnalysis,
            graph: DependencyGraph,
          ): ZIO[Any, TransformError, SpringBootProject] =
            ZIO.fail(TransformError.GeminiFailed("PROG1", "timeout")))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          mockDiscoveryAgent,
                          mockAnalyzerAgent,
                          mockMapperAgent,
                          failingTransformer,
                          mockValidationAgent,
                          mockDocumentationAgent,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
      test("propagates validation errors") {
        val failingValidation = ZLayer.succeed(new ValidationAgent:
          override def validate(
            project: SpringBootProject,
            analysis: CobolAnalysis,
          ): ZIO[Any, ValidationError, ValidationReport] =
            ZIO.fail(ValidationError.CompileFailed("prog1", "compilation error")))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          mockDiscoveryAgent,
                          mockAnalyzerAgent,
                          mockMapperAgent,
                          mockTransformerAgent,
                          failingValidation,
                          mockDocumentationAgent,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
      test("propagates documentation errors") {
        val failingDocs = ZLayer.succeed(new DocumentationAgent:
          override def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation] =
            ZIO.fail(DocError.InvalidResult("no projects")))

        for result <- MigrationOrchestrator
                        .runFullMigration(Paths.get("/cobol"), Paths.get("/output"))
                        .provide(
                          MigrationOrchestrator.live,
                          mockDiscoveryAgent,
                          mockAnalyzerAgent,
                          mockMapperAgent,
                          mockTransformerAgent,
                          mockValidationAgent,
                          failingDocs,
                          mockStateService,
                          mockGeminiService,
                        )
                        .either
        yield assertTrue(result.isLeft)
      },
    ),
    suite("runStep")(
      test("returns success for any step") {
        for
          result <- ZIO
                      .serviceWithZIO[MigrationOrchestrator](_.runStep(MigrationStep.Discovery))
                      .provide(MigrationOrchestrator.live, allMockLayers)
        yield assertTrue(
          result.success,
          result.step == MigrationStep.Discovery,
          result.error.isEmpty,
        )
      }
    ),
  )
