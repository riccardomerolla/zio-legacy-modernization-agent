package agents

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import core.FileService
import llm4zio.core.{ LlmService, LlmError, LlmResponse, LlmChunk, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import models.*

object JavaTransformerAgentSpec extends ZIOSpecDefault:

  class MockLlmService(responses: List[Any]) extends LlmService:
    private val responseQueue = new java.util.concurrent.ConcurrentLinkedQueue(responses.asJava)

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.attempt {
        val response = Option(responseQueue.poll()).getOrElse(
          throw new RuntimeException("No more mock responses available")
        )
        response.asInstanceOf[A]
      }.mapError(e => LlmError.ParseError("Mock error", e.getMessage))

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
      ZIO.succeed(true)

  private val sampleEntity: JavaEntity =
    JavaEntity(
      className = "Customer",
      packageName = "com.example.custprog.entity",
      fields = List(
        JavaField(name = "customerId", javaType = "Long", cobolSource = "CUSTOMER-ID", annotations = List("@Id"))
      ),
      annotations = List("@Entity", "@Table(name = \"customer\")"),
      sourceCode = "public class Customer {}"
    )

  private val sampleService: JavaService =
    JavaService(
      name = "CustomerService",
      methods = List(
        JavaMethod(name = "process", returnType = "String", parameters = List.empty, body = "return \"ok\";")
      )
    )

  private val sampleController: JavaController =
    JavaController(
      name = "CustomerController",
      basePath = "/api/customers",
      endpoints = List(
        RestEndpoint(path = "/process", method = HttpMethod.POST, methodName = "process")
      )
    )

  def spec: Spec[Any, Any] = suite("JavaTransformerAgentSpec")(
    test("transform generates Spring Boot project structure") {
      ZIO.scoped {
        for
          tempDir       <- ZIO.attemptBlocking(Files.createTempDirectory("transformer-spec"))
          analysis       = sampleAnalysis("CUSTPROG.cbl")
          graph          = DependencyGraph.empty
          project       <- JavaTransformerAgent
                             .transform(List(analysis), graph)
                             .provide(
                               FileService.live,
                               ZLayer.succeed(new MockLlmService(List(sampleEntity, sampleService, sampleController))),
                               ZLayer.succeed(
                                 MigrationConfig(sourceDir = tempDir, outputDir = tempDir, maxCompileRetries = 0)
                               ),
                               JavaTransformerAgent.live,
                             )
          projectDir     = tempDir.resolve(project.projectName.toLowerCase)
          pomExists     <- ZIO.attemptBlocking(Files.exists(projectDir.resolve("pom.xml")))
          appExists     <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("Application.java")
                             )
                           )
          openApiExists <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("config")
                                 .resolve("OpenApiConfig.java")
                             )
                           )
          repoExists    <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("repository")
                                 .resolve("CustomerRepository.java")
                             )
                           )
          yamlExists    <- ZIO.attemptBlocking(
                             Files.exists(projectDir.resolve("src/main/resources/application.yml"))
                           )
        yield assertTrue(
          project.projectName == "CUSTPROG",
          pomExists,
          appExists,
          openApiExists,
          repoExists,
          yamlExists,
        )
      }
    },
    test("transform sanitizes hyphenated COBOL filenames into valid Java packages") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("transformer-sanitize"))
          analysis = sampleAnalysis("format-balance.cbl")
          graph    = DependencyGraph.empty
          project <- JavaTransformerAgent
                       .transform(List(analysis), graph)
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(List(sampleEntity, sampleService, sampleController))),
                         ZLayer.succeed(
                           MigrationConfig(sourceDir = tempDir, outputDir = tempDir, maxCompileRetries = 0)
                         ),
                         JavaTransformerAgent.live,
                       )
        yield assertTrue(
          !project.projectName.contains("-"),
          project.projectName == "FORMATBALANCE",
        )
      }
    },
    test("transform uses config projectName when provided") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("transformer-override"))
          analysis = sampleAnalysis("CUSTPROG.cbl")
          graph    = DependencyGraph.empty
          project <- JavaTransformerAgent
                       .transform(List(analysis), graph)
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(List(sampleEntity, sampleService, sampleController))),
                         ZLayer.succeed(
                           MigrationConfig(
                             sourceDir = tempDir,
                             outputDir = tempDir,
                             projectName = Some("MyCustomProject"),
                             maxCompileRetries = 0,
                           )
                         ),
                         JavaTransformerAgent.live,
                       )
        yield assertTrue(
          project.projectName == "MyCustomProject"
        )
      }
    },
    test("transform uses config projectVersion in POM") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("transformer-version"))
          analysis = sampleAnalysis("CUSTPROG.cbl")
          graph    = DependencyGraph.empty
          project <- JavaTransformerAgent
                       .transform(List(analysis), graph)
                       .provide(
                         FileService.live,
                         ZLayer.succeed(new MockLlmService(List(sampleEntity, sampleService, sampleController))),
                         ZLayer.succeed(
                           MigrationConfig(
                             sourceDir = tempDir,
                             outputDir = tempDir,
                             projectVersion = "1.2.3",
                             maxCompileRetries = 0,
                           )
                         ),
                         JavaTransformerAgent.live,
                       )
        yield assertTrue(
          project.buildFile.content.contains("<version>1.2.3</version>"),
          project.buildFile.content.contains("<name>CUSTPROG</name>"),
        )
      }
    },
  )

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
        identification = Some("PROGRAM-ID."),
        environment = None,
        data = None,
        procedure = None,
      ),
      variables = List.empty,
      procedures = List.empty,
      copybooks = List.empty,
      complexity = ComplexityMetrics(1, 2, 1),
    )
