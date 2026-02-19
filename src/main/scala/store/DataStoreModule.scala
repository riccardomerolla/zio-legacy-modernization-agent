package store

import java.nio.file.Paths

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.config.{ GigaMapDefinition, GigaMapIndex }
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.service.EclipseStoreService

final case class TaskRunRow(
  id: String,
  sourceDir: String,
  outputDir: String,
  status: String,
  workflowId: Option[String],
  currentPhase: Option[String],
  errorMessage: Option[String],
  startedAt: String,
  completedAt: Option[String],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
) derives JsonCodec

final case class TaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: String,
) derives JsonCodec

final case class TaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: String,
) derives JsonCodec

final case class ConversationRow(
  id: String,
  title: String,
  description: Option[String],
  channelName: Option[String],
  status: String,
  createdAt: String,
  updatedAt: String,
  runId: Option[String],
  createdBy: Option[String],
) derives JsonCodec

final case class ChatMessageRow(
  id: String,
  conversationId: String,
  sender: String,
  senderType: String,
  content: String,
  messageType: String,
  metadata: Option[String],
  createdAt: String,
  updatedAt: String,
) derives JsonCodec

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: String,
) derives JsonCodec

final case class ActivityEventRow(
  id: String,
  eventType: String,
  source: String,
  runId: Option[String],
  conversationId: Option[String],
  agentName: Option[String],
  summary: String,
  payload: Option[String],
  createdAt: String,
) derives JsonCodec

object DataStoreModule:
  trait DataStoreService:
    def store: EclipseStoreService

  trait TaskRunsStore:
    def map: GigaMap[TaskRunId, TaskRunRow]

  trait TaskReportsStore:
    def map: GigaMap[ReportId, TaskReportRow]

  trait TaskArtifactsStore:
    def map: GigaMap[ArtifactId, TaskArtifactRow]

  trait ConversationsStore:
    def map: GigaMap[ConvId, ConversationRow]

  trait MessagesStore:
    def map: GigaMap[MessageId, ChatMessageRow]

  trait SessionContextsStore:
    def map: GigaMap[String, SessionContextRow]

  trait ActivityEventsStore:
    def map: GigaMap[EventId, ActivityEventRow]

  def taskRunsMap: URIO[TaskRunsStore, GigaMap[TaskRunId, TaskRunRow]] =
    ZIO.serviceWith[TaskRunsStore](_.map)

  def conversationsMap: URIO[ConversationsStore, GigaMap[ConvId, ConversationRow]] =
    ZIO.serviceWith[ConversationsStore](_.map)

  private val taskRunsDefinition = GigaMapDefinition[TaskRunId, TaskRunRow](
    name = "taskRuns",
    indexes = Chunk(
      GigaMapIndex.single("status", _.status),
      GigaMapIndex.single("createdAt", _.startedAt),
    ),
  )

  private val taskReportsDefinition = GigaMapDefinition[ReportId, TaskReportRow](
    name = "taskReports",
    indexes = Chunk(
      GigaMapIndex.single("taskRunId", _.taskRunId),
      GigaMapIndex.single("stepName", _.stepName),
    ),
  )

  private val taskArtifactsDefinition = GigaMapDefinition[ArtifactId, TaskArtifactRow](
    name = "taskArtifacts",
    indexes = Chunk(
      GigaMapIndex.single("taskRunId", _.taskRunId),
    ),
  )

  private val conversationsDefinition = GigaMapDefinition[ConvId, ConversationRow](
    name = "conversations",
    indexes = Chunk(
      GigaMapIndex.single("channelName", _.channelName.getOrElse("")),
      GigaMapIndex.single("status", _.status),
    ),
  )

  private val messagesDefinition = GigaMapDefinition[MessageId, ChatMessageRow](
    name = "messages",
    indexes = Chunk(
      GigaMapIndex.single("conversationId", _.conversationId),
      GigaMapIndex.single("createdAt", _.createdAt),
    ),
  )

  private val sessionContextsDefinition = GigaMapDefinition[String, SessionContextRow](
    name = "sessionContexts",
  )

  private val activityEventsDefinition = GigaMapDefinition[EventId, ActivityEventRow](
    name = "activityEvents",
    indexes = Chunk(
      GigaMapIndex.single("eventType", _.eventType),
      GigaMapIndex.single("createdAt", _.createdAt),
    ),
  )

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { storeConfig =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(storeConfig.dataStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(60L)),
        )
      }
    ) >>> EclipseStoreService.live

  val dataStore: ZLayer[EclipseStoreService, Nothing, DataStoreService] =
    ZLayer.fromFunction((svc: EclipseStoreService) =>
      new DataStoreService:
        override val store: EclipseStoreService = svc
    )

  val taskRuns: ZLayer[EclipseStoreService, GigaMapError, TaskRunsStore] =
    GigaMap.make(taskRunsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[TaskRunId, TaskRunRow]) =>
      new TaskRunsStore:
        override val map: GigaMap[TaskRunId, TaskRunRow] = gm
    )

  val taskReports: ZLayer[EclipseStoreService, GigaMapError, TaskReportsStore] =
    GigaMap.make(taskReportsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[ReportId, TaskReportRow]) =>
      new TaskReportsStore:
        override val map: GigaMap[ReportId, TaskReportRow] = gm
    )

  val taskArtifacts: ZLayer[EclipseStoreService, GigaMapError, TaskArtifactsStore] =
    GigaMap.make(taskArtifactsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[ArtifactId, TaskArtifactRow]) =>
      new TaskArtifactsStore:
        override val map: GigaMap[ArtifactId, TaskArtifactRow] = gm
    )

  val conversations: ZLayer[EclipseStoreService, GigaMapError, ConversationsStore] =
    GigaMap.make(conversationsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[ConvId, ConversationRow]) =>
      new ConversationsStore:
        override val map: GigaMap[ConvId, ConversationRow] = gm
    )

  val messages: ZLayer[EclipseStoreService, GigaMapError, MessagesStore] =
    GigaMap.make(messagesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[MessageId, ChatMessageRow]) =>
      new MessagesStore:
        override val map: GigaMap[MessageId, ChatMessageRow] = gm
    )

  val sessionContexts: ZLayer[EclipseStoreService, GigaMapError, SessionContextsStore] =
    GigaMap.make(sessionContextsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[String, SessionContextRow]) =>
      new SessionContextsStore:
        override val map: GigaMap[String, SessionContextRow] = gm
    )

  val activityEvents: ZLayer[EclipseStoreService, GigaMapError, ActivityEventsStore] =
    GigaMap.make(activityEventsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[EventId, ActivityEventRow]) =>
      new ActivityEventsStore:
        override val map: GigaMap[EventId, ActivityEventRow] = gm
    )

  private val mapsLive: ZLayer[
    EclipseStoreService,
    GigaMapError,
    DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
      SessionContextsStore & ActivityEventsStore,
  ] =
    ZLayer.makeSome[EclipseStoreService,
      DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
        SessionContextsStore & ActivityEventsStore
    ](
      dataStore,
      taskRuns,
      taskReports,
      taskArtifacts,
      conversations,
      messages,
      sessionContexts,
      activityEvents,
    )

  val live: ZLayer[
    StoreConfig,
    EclipseStoreError | GigaMapError,
    DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
      SessionContextsStore & ActivityEventsStore,
  ] =
    baseStore >>> mapsLive
