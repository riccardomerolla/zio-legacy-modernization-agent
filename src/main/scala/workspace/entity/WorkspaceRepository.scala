package workspace.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.store.ConfigStoreModule

trait WorkspaceRepository:
  def list: IO[PersistenceError, List[Workspace]]
  def get(id: String): IO[PersistenceError, Option[Workspace]]
  def save(ws: Workspace): IO[PersistenceError, Unit]
  def delete(id: String): IO[PersistenceError, Unit]

  def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]
  def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]
  def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit]
  def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit]

object WorkspaceRepository:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, WorkspaceRepository] =
    ZLayer.fromFunction(WorkspaceRepositoryES.apply)

final case class WorkspaceRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService
) extends WorkspaceRepository:

  private val ts = configStore.store

  private def wsKey(id: String): String  = s"workspace:$id"
  private def runKey(id: String): String = s"workspace-run:$id"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def list: IO[PersistenceError, List[Workspace]] =
    fetchByPrefix[Workspace]("workspace:", "listWorkspaces")
      .flatMap(ws => ZIO.foreach(ws)(migrateWorkspace))
      .map(_.sortBy(_.name.toLowerCase))

  override def get(id: String): IO[PersistenceError, Option[Workspace]] =
    ts.fetch[String, Workspace](wsKey(id))
      .mapError(storeErr("getWorkspace"))
      .flatMap(_.fold(ZIO.succeed(None))(ws => migrateWorkspace(ws).map(Some(_))))

  override def save(ws: Workspace): IO[PersistenceError, Unit] =
    ts.store(wsKey(ws.id), ws).mapError(storeErr("saveWorkspace"))

  override def delete(id: String): IO[PersistenceError, Unit] =
    ts.remove[String](wsKey(id)).mapError(storeErr("deleteWorkspace"))

  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
    fetchByPrefix[WorkspaceRun]("workspace-run:", "listRuns")
      .flatMap(runs => ZIO.foreach(runs)(migrateRun))
      .map(_.filter(_.workspaceId == workspaceId).sortBy(_.createdAt).reverse)

  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
    ts.fetch[String, WorkspaceRun](runKey(id))
      .mapError(storeErr("getRun"))
      .flatMap(_.fold(ZIO.succeed(None))(run => migrateRun(run).map(Some(_))))

  override def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit] =
    ts.store(runKey(run.id), run).mapError(storeErr("saveRun"))

  override def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit] =
    for
      existing <- getRun(runId).flatMap(
                    _.fold[IO[PersistenceError, WorkspaceRun]](
                      ZIO.fail(PersistenceError.NotFound("workspace-run", runId))
                    )(ZIO.succeed)
                  )
      now      <- Clock.instant
      _        <- saveRun(existing.copy(status = status, updatedAt = now))
    yield ()

  /** Repair a workspace whose `runMode` field was persisted before the RunMode ADT was introduced. EclipseStore
    * restores the object graph using the old class instances, so the value is non-null but belongs to a stale
    * classloader version that won't match the current `RunMode` cases. We detect this by attempting a match; on failure
    * we default to Host and re-save so the migration only runs once.
    */
  private def migrateWorkspace(ws: Workspace): IO[PersistenceError, Workspace] =
    val safeMode: RunMode =
      try
        ws.runMode match
          case RunMode.Host      => RunMode.Host
          case d: RunMode.Docker => d
      catch
        case _: MatchError => RunMode.Host
    // cliTool may be null for workspaces persisted before the field was added
    val safeTool: String  = Option(ws.cliTool).filter(_.nonEmpty).getOrElse("claude")
    val needsMigration    = !(safeMode eq ws.runMode) || safeTool != ws.cliTool
    if !needsMigration then ZIO.succeed(ws)
    else
      val migrated = ws.copy(runMode = safeMode, cliTool = safeTool)
      save(migrated).as(migrated) <*
        ZIO.logWarning(s"Migrated workspace ${ws.id} runMode/cliTool (stale binary record)")

  /** Repair a WorkspaceRun whose `status` field is a stale classloader instance of RunStatus. */
  private def migrateRun(run: WorkspaceRun): IO[PersistenceError, WorkspaceRun] =
    val safeStatus: RunStatus =
      try
        run.status match
          case RunStatus.Pending   => RunStatus.Pending
          case RunStatus.Running   => RunStatus.Running
          case RunStatus.Completed => RunStatus.Completed
          case RunStatus.Failed    => RunStatus.Failed
      catch case _: MatchError => RunStatus.Failed
    if safeStatus eq run.status then ZIO.succeed(run)
    else
      val migrated = run.copy(status = safeStatus)
      saveRun(migrated).as(migrated) <*
        ZIO.logWarning(s"Migrated run ${run.id} status to ${safeStatus} (stale binary record)")

  private def fetchByPrefix[V](
    prefix: String,
    op: String,
  )(using zio.schema.Schema[V]
  ): IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key => ts.fetch[String, V](key).mapError(storeErr(op))).map(_.flatten)
      )
