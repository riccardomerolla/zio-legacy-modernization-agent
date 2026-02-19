package web.controllers

import java.nio.file.{ Files, Paths }

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import db.*
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import models.*
import store.{ DataStoreModule, StoreConfig }
import web.ActivityHub

object SettingsControllerSpec extends ZIOSpecDefault:

  private val stubLlmService: LlmService = new LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse("ok"))

    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      ZStream.empty

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse("ok"))

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      ZStream.empty

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(None, Nil, "stop"))

    override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.ConfigError("not implemented in test"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(true)

  final private case class InMemoryConfigRepository(ref: Ref[Map[String, SettingRow]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.values.toList.sortBy(_.key))

    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key))

    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      Clock.instant.flatMap(now => ref.update(_.updated(key, SettingRow(key, value, now))))

    override def deleteSetting(key: String): IO[PersistenceError, Unit] =
      ref.update(_ - key).unit

    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
      ref.update(_.filterNot(_._1.startsWith(prefix))).unit

    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]          =
      ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]          =
      ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                       =
      ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))

    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
      ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
      ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
      ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  private def mkLayer
    : UIO[
      ZLayer[Any, Nothing, SettingsController & ConfigRepository & StoreConfig & DataStoreModule.TaskRunsStore]
    ] =
    for
      tempDir   <- ZIO.attemptBlocking(Files.createTempDirectory("settings-controller-spec")).orDie
      storeCfg   = StoreConfig(
                     configStorePath = tempDir.resolve("config-store").toString,
                     dataStorePath = tempDir.resolve("data-store").toString,
                   )
      configRef <- Ref.make(Map.empty[String, SettingRow])
      configRepo = InMemoryConfigRepository(configRef)
      hub        = new ActivityHub:
                     override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
                     override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent].map(identity)
    yield ZLayer.make[SettingsController & ConfigRepository & StoreConfig & DataStoreModule.TaskRunsStore](
      ZLayer.succeed(storeCfg),
      DataStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ZLayer.succeed(configRepo),
      ZLayer.succeed(hub),
      ZLayer.fromZIO(Ref.make(MigrationConfig())),
      ZLayer.succeed(stubLlmService),
      SettingsController.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SettingsControllerSpec")(
      test("POST /api/store/reset-data clears operational data and keeps config settings") {
        for
          layer       <- mkLayer
          controller  <- ZIO.service[SettingsController].provideLayer(layer)
          configRepo  <- ZIO.service[ConfigRepository].provideLayer(layer)
          storeConfig <- ZIO.service[StoreConfig].provideLayer(layer)
          _           <- configRepo.upsertSetting("spec.keep", "yes")
          response    <- controller.routes.runZIO(Request.post("/api/store/reset-data", Body.empty))
          body        <- response.body.asString
          setting     <- configRepo.getSetting("spec.keep")
          dataDirOk   <- ZIO.attemptBlocking(Files.exists(Paths.get(storeConfig.dataStorePath))).orDie
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("reset completed"),
          setting.exists(_.value == "yes"),
          dataDirOk,
        )
      }
    )
