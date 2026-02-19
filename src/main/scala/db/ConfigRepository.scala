package db

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import store.{ AgentId, ConfigStoreModule, WorkflowId }

trait ConfigRepository:
  // Settings
  def getAllSettings: IO[PersistenceError, List[SettingRow]]
  def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]
  def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]
  def upsertSettings(settings: Map[String, String]): IO[PersistenceError, Unit]   =
    ZIO.foreachDiscard(settings) { case (key, value) => upsertSetting(key, value) }
  def deleteSetting(key: String): IO[PersistenceError, Unit]
  def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))
  def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]

  // Workflows
  def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]
  def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]
  def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]
  def listWorkflows: IO[PersistenceError, List[WorkflowRow]]
  def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]
  def deleteWorkflow(id: Long): IO[PersistenceError, Unit]

  // Custom agents
  def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]
  def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]
  def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]]
  def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]
  def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]
  def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]

object ConfigRepository:
  // Settings
  def getAllSettings: ZIO[ConfigRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getAllSettings)

  def getSetting(key: String): ZIO[ConfigRepository, PersistenceError, Option[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getSetting(key))

  def upsertSetting(key: String, value: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.upsertSetting(key, value))

  def upsertSettings(settings: Map[String, String]): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.upsertSettings(settings))

  def deleteSetting(key: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteSetting(key))

  def getSettingsByPrefix(prefix: String): ZIO[ConfigRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getSettingsByPrefix(prefix))

  def deleteSettingsByPrefix(prefix: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteSettingsByPrefix(prefix))

  // Workflows
  def createWorkflow(workflow: WorkflowRow): ZIO[ConfigRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ConfigRepository](_.createWorkflow(workflow))

  def getWorkflow(id: Long): ZIO[ConfigRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getWorkflow(id))

  def getWorkflowByName(name: String): ZIO[ConfigRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getWorkflowByName(name))

  def listWorkflows: ZIO[ConfigRepository, PersistenceError, List[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listWorkflows)

  def updateWorkflow(workflow: WorkflowRow): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.updateWorkflow(workflow))

  def deleteWorkflow(id: Long): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteWorkflow(id))

  // Custom agents
  def createCustomAgent(agent: CustomAgentRow): ZIO[ConfigRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ConfigRepository](_.createCustomAgent(agent))

  def getCustomAgent(id: Long): ZIO[ConfigRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getCustomAgent(id))

  def getCustomAgentByName(name: String): ZIO[ConfigRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getCustomAgentByName(name))

  def listCustomAgents: ZIO[ConfigRepository, PersistenceError, List[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listCustomAgents)

  def updateCustomAgent(agent: CustomAgentRow): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.updateCustomAgent(agent))

  def deleteCustomAgent(id: Long): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteCustomAgent(id))

  val fromTaskRepository: ZLayer[TaskRepository, Nothing, ConfigRepository] =
    ZLayer.fromFunction((taskRepository: TaskRepository) =>
      new ConfigRepository:
        override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
          taskRepository.getAllSettings

        override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
          taskRepository.getSetting(key)

        override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
          taskRepository.upsertSetting(key, value)

        override def upsertSettings(settings: Map[String, String]): IO[PersistenceError, Unit] =
          ZIO.foreachDiscard(settings) { case (key, value) => taskRepository.upsertSetting(key, value) }

        override def deleteSetting(key: String): IO[PersistenceError, Unit] =
          ZIO.fail(PersistenceError.QueryFailed(
            "deleteSetting",
            s"Operation not supported by TaskRepository adapter: $key",
          ))

        override def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
          taskRepository.getSettingsByPrefix(prefix)

        override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
          taskRepository.deleteSettingsByPrefix(prefix)

        override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
          taskRepository.createWorkflow(workflow)

        override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
          taskRepository.getWorkflow(id)

        override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
          taskRepository.getWorkflowByName(name)

        override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
          taskRepository.listWorkflows

        override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
          taskRepository.updateWorkflow(workflow)

        override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
          taskRepository.deleteWorkflow(id)

        override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
          taskRepository.createCustomAgent(agent)

        override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
          taskRepository.getCustomAgent(id)

        override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
          taskRepository.getCustomAgentByName(name)

        override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
          taskRepository.listCustomAgents

        override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
          taskRepository.updateCustomAgent(agent)

        override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
          taskRepository.deleteCustomAgent(id)
    )

final case class ConfigRepositoryES(
  settings: GigaMap[String, String],
  workflows: GigaMap[WorkflowId, store.WorkflowRow],
  agents: GigaMap[AgentId, store.CustomAgentRow],
) extends ConfigRepository:

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent",
    "code-agent",
    "task-planner",
    "web-search-agent",
    "file-agent",
    "report-agent",
    "router-agent",
  )

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      rows <- settings.entries.mapError(storeError("getAllSettings"))
      now  <- Clock.instant
    yield rows.toList.map { case (key, raw) => decodeSetting(key, raw, now) }.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    for
      raw <- settings.get(key).mapError(storeError("getSetting"))
      now <- Clock.instant
    yield raw.map(value => decodeSetting(key, value, now))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- settings
               .put(key, StoredSetting(value, now).toJson)
               .mapError(storeError("upsertSetting"))
    yield ()

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    settings.remove(key).unit.mapError(storeError("deleteSetting"))

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- settings.keys.mapError(storeError("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.filter(_.startsWith(prefix))) { key =>
                settings.remove(key).unit.mapError(storeError("deleteSettingsByPrefix"))
              }
    yield ()

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- workflows
              .put(WorkflowId(id.toString), toStoreWorkflowRow(workflow.copy(id = Some(id))))
              .mapError(storeError("createWorkflow"))
    yield id

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    workflows
      .get(WorkflowId(id.toString))
      .map(_.flatMap(fromStoreWorkflowRow))
      .mapError(storeError("getWorkflow"))

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    queryAll(workflows, "getWorkflowByName")
      .map(_.toList.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    queryAll(workflows, "listWorkflows")
      .map(_.toList.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <- workflows.get(WorkflowId(id.toString)).mapError(storeError("updateWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- workflows
                    .put(WorkflowId(id.toString), toStoreWorkflowRow(workflow))
                    .mapError(storeError("updateWorkflow"))
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- workflows.get(WorkflowId(id.toString)).mapError(storeError("deleteWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- workflows.remove(WorkflowId(id.toString)).unit.mapError(storeError("deleteWorkflow"))
    yield ()

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- agents
              .put(AgentId(id.toString), toStoreAgentRow(agent.copy(id = Some(id))))
              .mapError(storeError("createCustomAgent"))
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    agents
      .get(AgentId(id.toString))
      .map(_.flatMap(fromStoreAgentRow))
      .mapError(storeError("getCustomAgent"))

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    queryAll(agents, "getCustomAgentByName")
      .map(_.toList.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    queryAll(agents, "listCustomAgents")
      .map(_.toList.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <- agents.get(AgentId(id.toString)).mapError(storeError("updateCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- agents
                    .put(AgentId(id.toString), toStoreAgentRow(agent))
                    .mapError(storeError("updateCustomAgent"))
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- agents.get(AgentId(id.toString)).mapError(storeError("deleteCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- agents.remove(AgentId(id.toString)).unit.mapError(storeError("deleteCustomAgent"))
    yield ()

  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name"))
    else ZIO.unit

  private def toStoreWorkflowRow(workflow: WorkflowRow): store.WorkflowRow =
    store.WorkflowRow(
      id = workflow.id.getOrElse(0L).toString,
      name = workflow.name,
      description = workflow.description,
      stepsJson = workflow.steps,
      isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt,
      updatedAt = workflow.updatedAt,
    )

  private def fromStoreWorkflowRow(workflow: store.WorkflowRow): Option[WorkflowRow] =
    workflow.id.toLongOption.map { parsedId =>
      WorkflowRow(
        id = Some(parsedId),
        name = workflow.name,
        description = workflow.description,
        steps = workflow.stepsJson,
        isBuiltin = workflow.isBuiltin,
        createdAt = workflow.createdAt,
        updatedAt = workflow.updatedAt,
      )
    }

  private def toStoreAgentRow(agent: CustomAgentRow): store.CustomAgentRow =
    store.CustomAgentRow(
      id = agent.id.getOrElse(0L).toString,
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description,
      systemPrompt = agent.systemPrompt,
      tagsJson = agent.tags,
      enabled = agent.enabled,
      createdAt = agent.createdAt,
      updatedAt = agent.updatedAt,
    )

  private def fromStoreAgentRow(agent: store.CustomAgentRow): Option[CustomAgentRow] =
    agent.id.toLongOption.map { parsedId =>
      CustomAgentRow(
        id = Some(parsedId),
        name = agent.name,
        displayName = agent.displayName,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        tags = agent.tagsJson,
        enabled = agent.enabled,
        createdAt = agent.createdAt,
        updatedAt = agent.updatedAt,
      )
    }

  private def queryAll[K, V](map: GigaMap[K, V], op: String): IO[PersistenceError, Chunk[V]] =
    map.query(GigaMapQuery.All()).mapError(storeError(op))

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeError(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeError(op: String)(throwable: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(throwable.getMessage).getOrElse(throwable.toString))

  final private case class StoredSetting(value: String, updatedAt: Instant) derives JsonCodec

  private def decodeSetting(key: String, raw: String, fallbackUpdatedAt: Instant): SettingRow =
    raw.fromJson[StoredSetting] match
      case Right(stored) => SettingRow(key = key, value = stored.value, updatedAt = stored.updatedAt)
      case Left(_)       => SettingRow(key = key, value = raw, updatedAt = fallbackUpdatedAt)

object ConfigRepositoryES:
  val live
    : ZLayer[
      ConfigStoreModule.SettingsStore & ConfigStoreModule.WorkflowsStore & ConfigStoreModule.CustomAgentsStore,
      Nothing,
      ConfigRepository,
    ] =
    ZLayer.fromZIO {
      for
        settings  <- ConfigStoreModule.settingsMap
        workflows <- ConfigStoreModule.workflowsMap
        agents    <- ConfigStoreModule.customAgentsMap
      yield ConfigRepositoryES(settings, workflows, agents)
    }
