package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import agent.entity.{ Agent, AgentRepository }
import db.{ ConfigRepository, CustomAgentRow, PersistenceError as DbPersistenceError, SettingRow, WorkflowRow }
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object AutoDispatcherSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T11:00:00Z")

  private def issue(
    id: String,
    priority: String,
    tags: List[String] = Nil,
    requiredCapabilities: List[String] = Nil,
  ): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = Some(TaskRunId(s"run-$id")),
      conversationId = Some(ConversationId(s"conv-$id")),
      title = s"Issue $id",
      description = s"Description for issue $id",
      issueType = "task",
      priority = priority,
      requiredCapabilities = requiredCapabilities,
      state = IssueState.Todo(now),
      tags = tags,
      blockedBy = Nil,
      blocking = Nil,
      contextPath = "",
      sourceFolder = "",
      workspaceId = Some("ws-1"),
    )

  private def agent(
    name: String,
    capabilities: List[String] = Nil,
    maxConcurrentRuns: Int = 1,
  ): Agent =
    Agent(
      id = AgentId(name),
      name = name,
      description = s"Agent $name",
      cliTool = "codex",
      capabilities = capabilities,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = maxConcurrentRuns,
      envVars = Map.empty,
      timeout = java.time.Duration.ofMinutes(10),
      enabled = true,
      createdAt = now,
      updatedAt = now,
    )

  private val workspace = Workspace(
    id = "ws-1",
    name = "Workspace 1",
    localPath = "/tmp/ws-1",
    defaultAgent = None,
    description = None,
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "codex",
    createdAt = now,
    updatedAt = now,
  )

  final private case class StubConfigRepository(settings: Map[String, String]) extends ConfigRepository:
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           =
      ZIO.succeed(settings.toList.map { case (key, value) => SettingRow(key, value, now) })
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                =
      ZIO.succeed(settings.get(key).map(value => SettingRow(key, value, now)))
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            = ZIO.unit
    override def deleteSetting(key: String): IO[DbPersistenceError, Unit]                           = ZIO.unit
    override def deleteSettingsByPrefix(prefix: String): IO[DbPersistenceError, Unit]               = ZIO.unit
    override def createWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Long]                = ZIO.dieMessage("unused")
    override def getWorkflow(id: Long): IO[DbPersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
    override def getWorkflowByName(name: String): IO[DbPersistenceError, Option[WorkflowRow]]       =
      ZIO.dieMessage("unused")
    override def listWorkflows: IO[DbPersistenceError, List[WorkflowRow]]                           = ZIO.dieMessage("unused")
    override def updateWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Unit]                = ZIO.dieMessage("unused")
    override def deleteWorkflow(id: Long): IO[DbPersistenceError, Unit]                             = ZIO.dieMessage("unused")
    override def createCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Long]             = ZIO.dieMessage("unused")
    override def getCustomAgent(id: Long): IO[DbPersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
    override def getCustomAgentByName(name: String): IO[DbPersistenceError, Option[CustomAgentRow]] =
      ZIO.dieMessage("unused")
    override def listCustomAgents: IO[DbPersistenceError, List[CustomAgentRow]]                     = ZIO.dieMessage("unused")
    override def updateCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Unit]             = ZIO.dieMessage("unused")
    override def deleteCustomAgent(id: Long): IO[DbPersistenceError, Unit]                          = ZIO.dieMessage("unused")

  final private case class StubIssueRepository(appended: Ref[List[IssueEvent]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             =
      appended.update(_ :+ event)
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   =
      ZIO.dieMessage("unused")

  final private case class StubDependencyResolver(ready: List[AgentIssue]) extends DependencyResolver:
    override def dependencyGraph(issues: List[AgentIssue]): Map[IssueId, Set[IssueId]] =
      issues.map(issue => issue.id -> issue.blockedBy.toSet).toMap
    override def readyToDispatch(issues: List[AgentIssue]): List[AgentIssue]           =
      ready
    override def currentIssues: IO[PersistenceError, List[AgentIssue]]                 =
      ZIO.succeed(ready)
    override def currentReadyToDispatch: IO[PersistenceError, List[AgentIssue]]        =
      ZIO.succeed(ready)

  final private case class StubAgentRepository(agents: List[Agent]) extends AgentRepository:
    override def append(event: _root_.agent.entity.AgentEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[PersistenceError, Agent]                             =
      ZIO
        .fromOption(agents.find(_.id == id))
        .orElseFail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]]          =
      ZIO.succeed(agents)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]             =
      ZIO.succeed(agents.find(_.name.equalsIgnoreCase(name)))

  final private case class StubWorkspaceRepository(runs: List[WorkspaceRun]) extends WorkspaceRepository:
    override def append(event: _root_.workspace.entity.WorkspaceEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("unused")
    override def list: IO[PersistenceError, List[Workspace]]                                       =
      ZIO.succeed(List(workspace))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                          =
      ZIO.succeed(Option.when(id == workspace.id)(workspace))
    override def delete(id: String): IO[PersistenceError, Unit]                                    =
      ZIO.dieMessage("unused")
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                   =
      ZIO.dieMessage("unused")
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]           =
      ZIO.succeed(Option.when(workspaceId == workspace.id)(runs).getOrElse(Nil))
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]    =
      ZIO.succeed(runs.filter(_.issueRef == issueRef))
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                    =
      ZIO.succeed(runs.find(_.id == id))

  final private case class StubWorkspaceRunService(assignments: Ref[List[AssignRunRequest]])
    extends WorkspaceRunService:
    private val noOpSlotRegistration = ZIO.unit

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      assignments.update(_ :+ req).as(
        WorkspaceRun(
          id = s"run-${req.issueRef.stripPrefix("#")}",
          workspaceId = workspaceId,
          parentRunId = None,
          issueRef = req.issueRef,
          agentName = req.agentName,
          prompt = req.prompt,
          conversationId = s"conv-${req.issueRef.stripPrefix("#")}",
          worktreePath = "/tmp/worktree",
          branchName = s"agent/${req.agentName}",
          status = RunStatus.Pending,
          attachedUsers = Set.empty,
          controllerUserId = None,
          createdAt = now,
          updatedAt = now,
        )
      )
    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String],
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.dieMessage("unused")
    override def cancelRun(runId: String): IO[WorkspaceError, Unit]                                   =
      ZIO.dieMessage("unused")
    override def registerSlot(runId: String, handle: SlotHandle): UIO[Unit]                           =
      noOpSlotRegistration

  final private case class RecordingWorkspaceRunService(
    assignments: Ref[List[AssignRunRequest]],
    registeredSlots: Ref[Map[String, SlotHandle]],
  ) extends WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      assignments.update(_ :+ req).as(
        WorkspaceRun(
          id = s"run-${req.issueRef.stripPrefix("#")}",
          workspaceId = workspaceId,
          parentRunId = None,
          issueRef = req.issueRef,
          agentName = req.agentName,
          prompt = req.prompt,
          conversationId = s"conv-${req.issueRef.stripPrefix("#")}",
          worktreePath = "/tmp/worktree",
          branchName = s"agent/${req.agentName}",
          status = RunStatus.Pending,
          attachedUsers = Set.empty,
          controllerUserId = None,
          createdAt = now,
          updatedAt = now,
        )
      )
    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String],
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.dieMessage("unused")
    override def cancelRun(runId: String): IO[WorkspaceError, Unit]                                   =
      ZIO.dieMessage("unused")
    override def registerSlot(runId: String, handle: SlotHandle): UIO[Unit]                           =
      registeredSlots.update(_ + (runId -> handle))

  final private case class StubActivityHub(events: Ref[List[ActivityEvent]]) extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] =
      events.update(_ :+ event)
    override def subscribe: UIO[Dequeue[ActivityEvent]]   =
      Queue.unbounded[ActivityEvent]

  final private case class StubAgentPoolManager(
    available: Map[String, Int] = Map.empty,
    acquired: Ref[List[String]],
  ) extends AgentPoolManager:
    override def acquireSlot(agentName: String): IO[PoolError, SlotHandle] =
      acquired.update(_ :+ agentName).as(SlotHandle(s"slot-$agentName", agentName, now))
    override def releaseSlot(handle: SlotHandle): UIO[Unit]                =
      ZIO.unit
    override def availableSlots(agentName: String): UIO[Int]               =
      ZIO.succeed(available.getOrElse(agentName, available.getOrElse(agentName.toLowerCase, 0)))
    override def resize(agentName: String, newMax: Int): UIO[Unit]         =
      ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AutoDispatcherSpec")(
      test("dispatchOnce is a no-op when auto-dispatch is disabled") {
        for
          appended    <- Ref.make(List.empty[IssueEvent])
          assignments <- Ref.make(List.empty[AssignRunRequest])
          activities  <- Ref.make(List.empty[ActivityEvent])
          acquired    <- Ref.make(List.empty[String])
          service      = AutoDispatcherLive(
                           configRepository = StubConfigRepository(Map(AutoDispatcher.enabledSettingKey -> "false")),
                           issueRepository = StubIssueRepository(appended),
                           dependencyResolver = StubDependencyResolver(List(issue("1", "high"))),
                           agentRepository = StubAgentRepository(List(agent("coder", maxConcurrentRuns = 1))),
                           workspaceRepository = StubWorkspaceRepository(Nil),
                           workspaceRunService = StubWorkspaceRunService(assignments),
                           activityHub = StubActivityHub(activities),
                           agentPoolManager = StubAgentPoolManager(Map("coder" -> 1), acquired),
                         )
          count       <- service.dispatchOnce
          gotRuns     <- assignments.get
          gotEvents   <- activities.get
          gotAcquired <- acquired.get
        yield assertTrue(
          count == 0,
          gotRuns.isEmpty,
          gotEvents.isEmpty,
          gotAcquired.isEmpty,
        )
      },
      test(
        "dispatchOnce acquires and registers a slot, applies rework boost, updates issue state, and emits activity"
      ) {
        val boosted = issue("1", "low", tags = List("rework"))
        val normal  = issue("2", "high")
        for
          appended    <- Ref.make(List.empty[IssueEvent])
          assignments <- Ref.make(List.empty[AssignRunRequest])
          activities  <- Ref.make(List.empty[ActivityEvent])
          acquired    <- Ref.make(List.empty[String])
          registered  <- Ref.make(Map.empty[String, SlotHandle])
          service      = AutoDispatcherLive(
                           configRepository = StubConfigRepository(Map(AutoDispatcher.enabledSettingKey -> "true")),
                           issueRepository = StubIssueRepository(appended),
                           dependencyResolver = StubDependencyResolver(List(normal, boosted)),
                           agentRepository = StubAgentRepository(List(agent("coder", maxConcurrentRuns = 1))),
                           workspaceRepository = StubWorkspaceRepository(Nil),
                           workspaceRunService = RecordingWorkspaceRunService(assignments, registered),
                           activityHub = StubActivityHub(activities),
                           agentPoolManager = StubAgentPoolManager(Map("coder" -> 1), acquired),
                         )
          count       <- service.dispatchOnce
          gotRuns     <- assignments.get
          gotEvents   <- appended.get
          activityLog <- activities.get
          gotAcquired <- acquired.get
          gotSlots    <- registered.get
        yield assertTrue(
          count == 1,
          gotRuns.map(_.issueRef) == List("#1"),
          gotAcquired == List("coder"),
          gotEvents.collect { case IssueEvent.Assigned(issueId, _, _, _) => issueId.value } == List("1"),
          gotEvents.collect { case IssueEvent.Started(issueId, _, _, _) => issueId.value } == List("1"),
          activityLog.size == 1,
          activityLog.head.summary.contains("issue #1"),
          gotSlots.keySet == Set("run-1"),
        )
      },
      test("dispatchOnce skips dispatch when the pool has no available slots") {
        val ready = issue("3", "critical", requiredCapabilities = List("scala"))
        for
          appended    <- Ref.make(List.empty[IssueEvent])
          assignments <- Ref.make(List.empty[AssignRunRequest])
          activities  <- Ref.make(List.empty[ActivityEvent])
          acquired    <- Ref.make(List.empty[String])
          service      = AutoDispatcherLive(
                           configRepository = StubConfigRepository(Map(AutoDispatcher.enabledSettingKey -> "true")),
                           issueRepository = StubIssueRepository(appended),
                           dependencyResolver = StubDependencyResolver(List(ready)),
                           agentRepository =
                             StubAgentRepository(List(agent("busy", capabilities = List("scala"), maxConcurrentRuns = 2))),
                           workspaceRepository = StubWorkspaceRepository(Nil),
                           workspaceRunService = StubWorkspaceRunService(assignments),
                           activityHub = StubActivityHub(activities),
                           agentPoolManager = StubAgentPoolManager(Map("busy" -> 0), acquired),
                         )
          count       <- service.dispatchOnce
          gotRuns     <- assignments.get
          gotAcquired <- acquired.get
        yield assertTrue(
          count == 0,
          gotRuns.isEmpty,
          gotAcquired.isEmpty,
        )
      },
    )
