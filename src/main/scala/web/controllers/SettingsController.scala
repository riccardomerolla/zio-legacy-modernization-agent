package web.controllers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths, StandardOpenOption }

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.SettingsApplier
import db.*
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import llm4zio.core.{ LlmError, LlmService }
import models.{ ActivityEvent, ActivityEventType, GatewayConfig }
import store.{ ConfigStoreModule, DataStoreModule, StoreConfig }
import web.views.{ HtmlViews, SettingsView }
import web.{ ActivityHub, ErrorHandlingMiddleware }

trait SettingsController:
  def routes: Routes[Any, Response]

object SettingsController:

  def routes: ZIO[SettingsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SettingsController](_.routes)

  val live
    : ZLayer[
      ConfigRepository & ActivityHub & Ref[GatewayConfig] & LlmService & ConfigStoreModule.ConfigStoreService &
        DataStoreModule.DataStoreService & StoreConfig &
        DataStoreModule.TaskRunsStore &
        DataStoreModule.TaskReportsStore &
        DataStoreModule.TaskArtifactsStore &
        DataStoreModule.ConversationsStore &
        DataStoreModule.MessagesStore &
        DataStoreModule.SessionContextsStore &
        DataStoreModule.ActivityEventsStore &
        DataStoreModule.AgentIssuesStore &
        DataStoreModule.AgentAssignmentsStore &
        DataStoreModule.MemoryEntriesStore,
      Nothing,
      SettingsController,
    ] =
    ZLayer.fromFunction(SettingsControllerLive.apply)

final case class SettingsControllerLive(
  repository: ConfigRepository,
  activityHub: ActivityHub,
  configRef: Ref[GatewayConfig],
  llmService: LlmService,
  configStoreService: ConfigStoreModule.ConfigStoreService,
  dataStoreService: DataStoreModule.DataStoreService,
  storeConfig: StoreConfig,
  taskRunsStore: DataStoreModule.TaskRunsStore,
  taskReportsStore: DataStoreModule.TaskReportsStore,
  taskArtifactsStore: DataStoreModule.TaskArtifactsStore,
  conversationsStore: DataStoreModule.ConversationsStore,
  messagesStore: DataStoreModule.MessagesStore,
  sessionContextsStore: DataStoreModule.SessionContextsStore,
  activityEventsStore: DataStoreModule.ActivityEventsStore,
  agentIssuesStore: DataStoreModule.AgentIssuesStore,
  agentAssignmentsStore: DataStoreModule.AgentAssignmentsStore,
  memoryEntriesStore: DataStoreModule.MemoryEntriesStore,
) extends SettingsController:

  private val settingsKeys: List[String] = List(
    "ai.provider",
    "ai.model",
    "ai.baseUrl",
    "ai.apiKey",
    "ai.timeout",
    "ai.maxRetries",
    "ai.requestsPerMinute",
    "ai.burstSize",
    "ai.acquireTimeout",
    "ai.temperature",
    "ai.maxTokens",
    "gateway.name",
    "gateway.dryRun",
    "gateway.verbose",
    "telegram.enabled",
    "telegram.botToken",
    "telegram.mode",
    "telegram.secretToken",
    "telegram.webhookUrl",
    "telegram.polling.interval",
    "telegram.polling.batchSize",
    "telegram.polling.timeout",
    "memory.enabled",
    "memory.maxContextMemories",
    "memory.summarizationThreshold",
    "memory.retentionDays",
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings"                      -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          rows    <- repository.getAllSettings
          settings = rows.map(r => r.key -> r.value).toMap
        yield html(HtmlViews.settingsPage(settings))
      }
    },
    Method.POST / "settings"                     -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form     <- parseForm(req)
          _        <- ZIO.foreachDiscard(settingsKeys) { key =>
                        val value = key match
                          case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" =>
                            if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                          case _                                                                            =>
                            form.getOrElse(key, "")
                        if value.nonEmpty || key.startsWith("ai.") || key.startsWith("gateway.") || key.startsWith(
                            "telegram."
                          ) || key
                            .startsWith("memory.")
                        then
                          repository.upsertSetting(key, value)
                        else ZIO.unit
                      }
          _        <- checkpointConfigStore
          rows     <- repository.getAllSettings
          saved     = rows.map(r => r.key -> r.value).toMap
          newConfig = SettingsApplier.toGatewayConfig(saved)
          _        <- configRef.set(newConfig)
          _        <- writeSettingsSnapshot(saved)
          now      <- Clock.instant
          _        <- activityHub.publish(
                        ActivityEvent(
                          eventType = ActivityEventType.ConfigChanged,
                          source = "settings",
                          summary = "Application settings updated",
                          createdAt = now,
                        )
                      )
        yield html(HtmlViews.settingsPage(saved, Some("Settings saved successfully.")))
      }
    },
    Method.POST / "api" / "store" / "reset-data" -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          _   <- resetDataStore
          now <- Clock.instant
          _   <- activityHub.publish(
                   ActivityEvent(
                     eventType = ActivityEventType.ConfigChanged,
                     source = "settings",
                     summary = "Operational data store reset",
                     createdAt = now,
                   )
                 )
        yield Response(
          status = Status.Ok,
          headers = Headers(Header.Custom("HX-Redirect", "/")),
          body = Body.fromString("Data store reset completed."),
        )
      }
    },
    Method.POST / "api" / "settings" / "test-ai" -> handler { (req: Request) =>
      testAIConnection(req)
    },
  )

  private def resetDataStore: IO[PersistenceError, Unit] =
    for
      _ <- safeReset("taskRuns")(taskRunsStore.map.clear)
      _ <- safeReset("taskReports")(taskReportsStore.map.clear)
      _ <- safeReset("taskArtifacts")(taskArtifactsStore.map.clear)
      _ <- safeReset("conversations")(conversationsStore.map.clear)
      _ <- safeReset("messages")(messagesStore.map.clear)
      _ <- safeReset("sessionContexts")(sessionContextsStore.map.clear)
      _ <- safeReset("activityEvents")(activityEventsStore.map.clear)
      _ <- safeReset("agentIssues")(agentIssuesStore.map.clear)
      _ <- safeReset("agentAssignments")(agentAssignmentsStore.map.clear)
      _ <- safeReset("memoryEntries")(memoryEntriesStore.map.clear)
      _ <- safeReset("reloadRoots")(dataStoreService.store.reloadRoots)
    yield ()

  private def safeReset(name: String)(effect: ZIO[Any, Any, Unit]): UIO[Unit] =
    effect.catchAll(err => ZIO.logWarning(s"data reset step '$name' failed: $err"))

  private def checkpointConfigStore: IO[PersistenceError, Unit] =
    for
      status <- configStoreService.store
                  .maintenance(LifecycleCommand.Checkpoint)
                  .mapError(err => PersistenceError.QueryFailed("config_checkpoint", err.toString))
      _      <- status match
                  case LifecycleStatus.Failed(message) =>
                    ZIO.fail(PersistenceError.QueryFailed("config_checkpoint", s"checkpoint failed: $message"))
                  case _                               => ZIO.unit
      _      <- configStoreService.store
                  .reloadRoots
                  .mapError(err => PersistenceError.QueryFailed("config_checkpoint", s"reloadRoots failed: $err"))
    yield ()

  private def writeSettingsSnapshot(settings: Map[String, String]): IO[PersistenceError, Unit] =
    ZIO
      .attemptBlocking {
        val configStorePath = Paths.get(storeConfig.configStorePath)
        Files.createDirectories(configStorePath)
        val snapshot        = configStorePath.resolve("settings.snapshot.json")
        Files.writeString(
          snapshot,
          settings.toJson,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
        )
      }
      .mapError(err =>
        PersistenceError.QueryFailed("settings_snapshot", Option(err.getMessage).getOrElse(err.toString))
      )
      .unit

  private def testAIConnection(req: Request): UIO[Response] =
    val test =
      for
        form     <- parseForm(req)
        aiConfig <- ZIO.fromOption(SettingsApplier.toAIProviderConfig(form)).orElseFail("No AI provider configured")
        start    <- Clock.nanoTime
        _        <- llmService.execute("Say 'pong'")
        end      <- Clock.nanoTime
        latency   = (end - start) / 1_000_000 // Convert nanos to millis
      yield (aiConfig.model, latency)

    test
      .fold(
        error => {
          val errorMessage = error match
            case e: LlmError => formatLlmError(e)
            case msg: String => msg
            case _           => "Unknown error"
          SettingsView.testConnectionError(errorMessage)
        },
        {
          case (model, latency) =>
            SettingsView.testConnectionSuccess(model, latency)
        },
      )
      .map { htmlString =>
        Response.text(htmlString).contentType(MediaType.text.html)
      }

  private def formatLlmError(error: LlmError): String =
    error match
      case LlmError.ProviderError(message, _)    => message
      case LlmError.RateLimitError(_)            => "Rate limited: Too many requests"
      case LlmError.AuthenticationError(message) => s"Authentication failed: $message"
      case LlmError.InvalidRequestError(message) => s"Invalid request: $message"
      case LlmError.TimeoutError(duration)       => s"Request timed out after ${duration.toSeconds}s"
      case LlmError.ParseError(message, _)       => s"Parse error: $message"
      case LlmError.ToolError(toolName, message) => s"Tool error ($toolName): $message"
      case LlmError.ConfigError(message)         => s"Configuration error: $message"

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap { kv =>
            kv.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)
