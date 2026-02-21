package store

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.schema.{ Schema, derived }

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStore, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }

// ---------------------------------------------------------------------------
// Config-store row types — persistence-only; Schema handles Option/Instant.
// ---------------------------------------------------------------------------

final case class WorkflowRow(
  id: String,
  name: String,
  description: Option[String],
  stepsJson: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class CustomAgentRow(
  id: String,
  name: String,
  displayName: String,
  description: Option[String],
  systemPrompt: String,
  tagsJson: Option[String],
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

private val configStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[WorkflowRow])
    ++ SchemaBinaryCodec.handlers(Schema[CustomAgentRow])

object ConfigStoreModule:

  /** Config-store service exposes a TypedStore for schema-validated CRUD and the raw EclipseStoreService for key-prefix
    * scanning (streamKeys). Used for settings, workflows, and custom agents.
    */
  trait ConfigStoreService:
    def store: TypedStore
    def rawStore: EclipseStoreService

  private val withShutdownCheckpoint: ZLayer[EclipseStoreService, Nothing, EclipseStoreService] =
    ZLayer.scoped {
      for
        svc <- ZIO.service[EclipseStoreService]
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Config store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Config store: shutdown checkpoint complete.")
               )
      yield svc
    }

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.configStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = configStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live >>> withShutdownCheckpoint

  val configStore: ZLayer[EclipseStoreService, Nothing, ConfigStoreService] =
    ZLayer.fromFunction((esc: EclipseStoreService) =>
      new ConfigStoreService:
        override val store: TypedStore             = TypedStoreLive(esc)
        override val rawStore: EclipseStoreService = esc
    )

  val live: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreService] =
    baseStore >>> configStore
