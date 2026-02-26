package workspace.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import _root_.config.entity.*
import conversation.entity.api.*
import db.{ ChatRepository, PersistenceError }
import issues.entity.api.{ AgentAssignment, AgentIssue, IssueStatus }
import orchestration.control.AgentRegistry
import taskrun.entity.TaskStep
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesControllerSpec extends ZIOSpecDefault:

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("code-agent"),
    description = None,
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private class StubWorkspaceRepo(ref: Ref[Map[String, Workspace]]) extends WorkspaceRepository:
    def list: IO[shared.errors.PersistenceError, List[Workspace]]                           = ref.get.map(_.values.toList)
    def get(id: String): IO[shared.errors.PersistenceError, Option[Workspace]]              = ref.get.map(_.get(id))
    def save(ws: Workspace): IO[shared.errors.PersistenceError, Unit]                       = ref.update(_ + (ws.id -> ws))
    def delete(id: String): IO[shared.errors.PersistenceError, Unit]                        = ref.update(_ - id)
    def listRuns(wid: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]]       = ZIO.succeed(Nil)
    def getRun(id: String): IO[shared.errors.PersistenceError, Option[WorkspaceRun]]        = ZIO.succeed(None)
    def saveRun(r: WorkspaceRun): IO[shared.errors.PersistenceError, Unit]                  = ZIO.unit
    def updateRunStatus(id: String, s: RunStatus): IO[shared.errors.PersistenceError, Unit] = ZIO.unit

  private class StubRunService extends WorkspaceRunService:
    def assign(wid: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(wid))
    def continueRun(runId: String, followUp: String): IO[WorkspaceError, Unit]       = ZIO.unit

  private object StubChatRepo extends ChatRepository:
    def createConversation(c: ChatConversation): IO[PersistenceError, Long]                        = ZIO.succeed(1L)
    def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                  = ZIO.succeed(None)
    def listConversations(o: Int, l: Int): IO[PersistenceError, List[ChatConversation]]            = ZIO.succeed(Nil)
    def getConversationsByChannel(ch: String): IO[PersistenceError, List[ChatConversation]]        = ZIO.succeed(Nil)
    def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]          = ZIO.succeed(Nil)
    def updateConversation(c: ChatConversation): IO[PersistenceError, Unit]                        = ZIO.unit
    def deleteConversation(id: Long): IO[PersistenceError, Unit]                                   = ZIO.unit
    def addMessage(m: ConversationEntry): IO[PersistenceError, Long]                               = ZIO.succeed(1L)
    def getMessages(cid: Long): IO[PersistenceError, List[ConversationEntry]]                      = ZIO.succeed(Nil)
    def getMessagesSince(cid: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] = ZIO.succeed(Nil)
    def createIssue(i: AgentIssue): IO[PersistenceError, Long]                                     = ZIO.succeed(1L)
    def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]]                               = ZIO.succeed(None)
    def listIssues(o: Int, l: Int): IO[PersistenceError, List[AgentIssue]]                         = ZIO.succeed(Nil)
    def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]]                       = ZIO.succeed(Nil)
    def listIssuesByStatus(s: IssueStatus): IO[PersistenceError, List[AgentIssue]]                 = ZIO.succeed(Nil)
    def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]]                  = ZIO.succeed(Nil)
    def updateIssue(i: AgentIssue): IO[PersistenceError, Unit]                                     = ZIO.unit
    def deleteIssue(id: Long): IO[PersistenceError, Unit]                                          = ZIO.unit
    def assignIssueToAgent(iid: Long, name: String): IO[PersistenceError, Unit]                    = ZIO.unit
    def createAssignment(a: AgentAssignment): IO[PersistenceError, Long]                           = ZIO.succeed(1L)
    def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]]                     = ZIO.succeed(None)
    def listAssignmentsByIssue(iid: Long): IO[PersistenceError, List[AgentAssignment]]             = ZIO.succeed(Nil)
    def updateAssignment(a: AgentAssignment): IO[PersistenceError, Unit]                           = ZIO.unit

  private object StubAgentRegistry extends AgentRegistry:
    def registerAgent(r: RegisterAgentRequest): UIO[AgentInfo]                  =
      ZIO.succeed(AgentInfo(r.name, r.name, r.displayName, r.description, r.agentType, r.usesAI, r.tags))
    def findByName(name: String): UIO[Option[AgentInfo]]                        = ZIO.succeed(None)
    def findAgents(q: AgentQuery): UIO[List[AgentInfo]]                         = ZIO.succeed(Nil)
    def getAllAgents: UIO[List[AgentInfo]]                                      = ZIO.succeed(AgentRegistry.builtInAgents)
    def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]                = ZIO.succeed(Nil)
    def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]                 = ZIO.succeed(Nil)
    def findAgentsForTransformation(i: String, o: String): UIO[List[AgentInfo]] = ZIO.succeed(Nil)
    def recordInvocation(name: String, ok: Boolean, ms: Long): UIO[Unit]        = ZIO.unit
    def updateHealth(name: String, ok: Boolean, msg: Option[String]): UIO[Unit] = ZIO.unit
    def setAgentEnabled(name: String, enabled: Boolean): UIO[Unit]              = ZIO.unit
    def getMetrics(name: String): UIO[Option[AgentMetrics]]                     = ZIO.succeed(None)
    def getHealth(name: String): UIO[Option[AgentHealth]]                       = ZIO.succeed(None)
    def loadCustomAgents(rows: List[db.CustomAgentRow]): UIO[Int]               = ZIO.succeed(0)
    def getRankedAgents(q: AgentQuery): UIO[List[AgentInfo]]                    = ZIO.succeed(Nil)

  private def makeRoutes(wsRef: Ref[Map[String, Workspace]]) =
    WorkspacesController.routes(
      StubWorkspaceRepo(wsRef),
      StubRunService(),
      StubChatRepo,
      StubAgentRegistry,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspacesControllerSpec")(
    test("GET /settings/workspaces returns 200") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        routes = makeRoutes(wsRef)
        req    = Request.get(URL(Path.decode("/settings/workspaces")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /api/workspaces returns JSON list") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        routes = makeRoutes(wsRef)
        req    = Request.get(URL(Path.decode("/api/workspaces")))
        resp  <- routes.runZIO(req)
        body  <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("my-api"))
    },
    test("DELETE /api/workspaces/:id returns 204") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        routes = makeRoutes(wsRef)
        req    = Request(method = Method.DELETE, url = URL(Path.decode("/api/workspaces/ws-1")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /api/workspaces/:id/runs returns 200") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        routes = makeRoutes(wsRef)
        req    = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("WorkspaceCreateRequest with default RunMode.Host round-trips through JSON") {
      val req     = WorkspaceCreateRequest(
        name = "my-api",
        localPath = "/tmp/my-api",
        defaultAgent = Some("code-agent"),
        description = None,
      )
      val decoded = req.toJson.fromJson[WorkspaceCreateRequest]
      assertTrue(decoded == Right(req) && decoded.exists(_.runMode == RunMode.Host))
    },
    test("WorkspaceCreateRequest with RunMode.Docker round-trips through JSON") {
      val req     = WorkspaceCreateRequest(
        name = "sandboxed",
        localPath = "/tmp/sandboxed",
        defaultAgent = Some("code-agent"),
        description = None,
        runMode = RunMode.Docker(image = "opencode:latest", network = Some("none")),
      )
      val decoded = req.toJson.fromJson[WorkspaceCreateRequest]
      assertTrue(decoded == Right(req))
    },
  )
