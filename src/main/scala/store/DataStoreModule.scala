package store

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.schema.{ Schema, derived }

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.config.{ GigaMapDefinition, GigaMapIndex }
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.gigamap.vector.VectorIndexService
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStore, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import memory.MemoryEntry
import models.{ ActivityEventType, IssuePriority, IssueStatus, MessageType, SenderType }

// ---------------------------------------------------------------------------
// Store-level row types — persistence-only; use Schema for binary serialization
// (Option, Instant, typed enums are handled correctly by SchemaBinaryCodec).
// ---------------------------------------------------------------------------

final case class TaskRunRow(
  id: String,
  sourceDir: String,
  outputDir: String,
  status: String,
  workflowId: Option[String],
  currentPhase: Option[String],
  errorMessage: Option[String],
  startedAt: Instant,
  completedAt: Option[Instant],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
) derives Schema

final case class TaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives Schema

final case class TaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives Schema

final case class ConversationRow(
  id: Long,
  title: String,
  description: Option[String],
  channelName: Option[String],
  status: String,
  createdAt: Instant,
  updatedAt: Instant,
  runId: Option[Long],
  createdBy: Option[String],
) derives Schema

final case class ChatMessageRow(
  id: Long,
  conversationId: Long,
  sender: String,
  senderType: SenderType,
  content: String,
  messageType: MessageType,
  metadata: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives Schema

final case class ActivityEventRow(
  id: String,
  eventType: ActivityEventType,
  source: String,
  runId: Option[String],
  conversationId: Option[String],
  agentName: Option[String],
  summary: String,
  payload: Option[String],
  createdAt: Instant,
) derives Schema

final case class AgentIssueRow(
  id: Long,
  runId: Option[Long],
  conversationId: Option[Long],
  title: String,
  description: String,
  issueType: String,
  tags: Option[String],
  preferredAgent: Option[String],
  contextPath: Option[String],
  sourceFolder: Option[String],
  priority: IssuePriority,
  status: IssueStatus,
  assignedAgent: Option[String],
  assignedAt: Option[Instant],
  completedAt: Option[Instant],
  errorMessage: Option[String],
  resultData: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class AgentAssignmentRow(
  id: Long,
  issueId: Long,
  agentName: String,
  status: String,
  assignedAt: Instant,
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  executionLog: Option[String],
  result: Option[String],
) derives Schema

// ---------------------------------------------------------------------------
// Schema binary handlers — registered once at store initialisation so that
// EclipseStore uses the ZIO-Schema JSON-payload codec for every row type. This
// correctly handles Option, Instant, and typed enums without reflection-based
// null surprises.
// ---------------------------------------------------------------------------

private val dataStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[ConversationRow])
    ++ SchemaBinaryCodec.handlers(Schema[ChatMessageRow])
    ++ SchemaBinaryCodec.handlers(Schema[SessionContextRow])
    ++ SchemaBinaryCodec.handlers(Schema[AgentIssueRow])
    ++ SchemaBinaryCodec.handlers(Schema[AgentAssignmentRow])
    ++ SchemaBinaryCodec.handlers(Schema[ActivityEventRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRunRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskReportRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskArtifactRow])
    ++ SchemaBinaryCodec.handlers(Schema[SenderType])
    ++ SchemaBinaryCodec.handlers(Schema[MessageType])
    ++ SchemaBinaryCodec.handlers(Schema[ActivityEventType])
    ++ SchemaBinaryCodec.handlers(Schema[IssuePriority])
    ++ SchemaBinaryCodec.handlers(Schema[IssueStatus])

object DataStoreModule:

  /** DataStoreService exposes a TypedStore for schema-validated CRUD and the raw EclipseStoreService for key-prefix
    * scanning (streamKeys). The GigaMap is retained only for MemoryEntriesStore (vector search).
    */
  trait DataStoreService:
    def store: TypedStore
    def rawStore: EclipseStoreService

  trait MemoryEntriesStore:
    def map: GigaMap[MemoryId, MemoryEntry]

  def memoryEntriesMap: URIO[MemoryEntriesStore, GigaMap[MemoryId, MemoryEntry]] =
    ZIO.serviceWith[MemoryEntriesStore](_.map)

  private val memoryEntriesDefinition = GigaMapDefinition[MemoryId, MemoryEntry](
    name = "memoryEntries",
    indexes = Chunk(
      GigaMapIndex.single("userId", _.userId.value),
      GigaMapIndex.single("kind", _.kind.value),
    ),
  )

  /** Shutdown-checkpoint finalizer layered on top of EclipseStoreService. */
  private val withShutdownCheckpoint: ZLayer[EclipseStoreService, Nothing, EclipseStoreService] =
    ZLayer.scoped {
      for
        svc <- ZIO.service[EclipseStoreService]
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Data store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Data store: shutdown checkpoint complete.")
               )
      yield svc
    }

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.dataStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = dataStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live >>> withShutdownCheckpoint

  val dataStore: ZLayer[EclipseStoreService, Nothing, DataStoreService] =
    ZLayer.fromFunction((esc: EclipseStoreService) =>
      new DataStoreService:
        override val store: TypedStore             = TypedStoreLive(esc)
        override val rawStore: EclipseStoreService = esc
    )

  val memoryEntries: ZLayer[EclipseStoreService, GigaMapError, MemoryEntriesStore] =
    GigaMap.make(memoryEntriesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[MemoryId, MemoryEntry]) =>
      new MemoryEntriesStore:
        override val map: GigaMap[MemoryId, MemoryEntry] = gm
    )

  val live: ZLayer[
    StoreConfig,
    EclipseStoreError | GigaMapError,
    DataStoreService & MemoryEntriesStore & VectorIndexService,
  ] =
    baseStore >>> (dataStore ++ memoryEntries ++ VectorIndexService.live)
