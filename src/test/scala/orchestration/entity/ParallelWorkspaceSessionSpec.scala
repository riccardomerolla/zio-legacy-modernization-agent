package orchestration.entity

import java.time.Instant

import zio.json.*
import zio.test.*

object ParallelWorkspaceSessionSpec extends ZIOSpecDefault:
  def spec: Spec[Environment, Any] = suite("ParallelWorkspaceSessionSpec")(
    suite("DiffStats")(
      test("round-trips through JSON") {
        val stats = DiffStats(filesChanged = 8, linesAdded = 142, linesRemoved = 12)
        assertTrue(stats.toJson.fromJson[DiffStats] == Right(stats))
      }
    ),
    suite("WorktreeRunStatus")(
      test("all values round-trip through JSON") {
        val statuses = List(
          WorktreeRunStatus.Pending,
          WorktreeRunStatus.Running,
          WorktreeRunStatus.Completed,
          WorktreeRunStatus.Failed,
          WorktreeRunStatus.Cancelled,
        )
        assertTrue(statuses.forall(s => s.toJson.fromJson[WorktreeRunStatus] == Right(s)))
      }
    ),
    suite("ParallelSessionStatus")(
      test("all values round-trip through JSON") {
        val statuses = List(
          ParallelSessionStatus.Pending,
          ParallelSessionStatus.Running,
          ParallelSessionStatus.Collecting,
          ParallelSessionStatus.ReadyForReview,
          ParallelSessionStatus.Completed,
          ParallelSessionStatus.Failed,
          ParallelSessionStatus.Cancelled,
        )
        assertTrue(statuses.forall(s => s.toJson.fromJson[ParallelSessionStatus] == Right(s)))
      }
    ),
    suite("WorktreeRunRef")(
      test("round-trips through JSON with all fields") {
        val ref = WorktreeRunRef(
          stepId = "step-1",
          workspaceRunId = "run-abc",
          agentName = "claude",
          branch = "parallel/feature-abc123",
          status = WorktreeRunStatus.Completed,
          summary = Some("Implemented auth module"),
          diffStats = Some(DiffStats(filesChanged = 5, linesAdded = 200, linesRemoved = 10)),
        )
        assertTrue(ref.toJson.fromJson[WorktreeRunRef] == Right(ref))
      },
      test("round-trips through JSON with optional fields absent") {
        val ref = WorktreeRunRef(
          stepId = "step-2",
          workspaceRunId = "run-xyz",
          agentName = "gemini",
          branch = "parallel/tests-xyz789",
          status = WorktreeRunStatus.Pending,
          summary = None,
          diffStats = None,
        )
        assertTrue(ref.toJson.fromJson[WorktreeRunRef] == Right(ref))
      },
    ),
    suite("ParallelWorkspaceSession")(
      test("round-trips through JSON") {
        val now     = Instant.parse("2026-03-04T10:00:00Z")
        val session = ParallelWorkspaceSession(
          id = "session-1",
          workflowId = "wf-42",
          correlationId = "corr-abc",
          status = ParallelSessionStatus.Running,
          baseBranch = "main",
          worktreeRuns = List(
            WorktreeRunRef(
              stepId = "step-1",
              workspaceRunId = "run-1",
              agentName = "claude",
              branch = "parallel/feature-abc",
              status = WorktreeRunStatus.Running,
              summary = None,
              diffStats = None,
            )
          ),
          createdAt = now,
          updatedAt = now,
          completedAt = None,
          requestedBy = Some("user-123"),
        )
        assertTrue(session.toJson.fromJson[ParallelWorkspaceSession] == Right(session))
      },
      test("round-trips through JSON with completedAt") {
        val now     = Instant.parse("2026-03-04T10:00:00Z")
        val later   = Instant.parse("2026-03-04T10:30:00Z")
        val session = ParallelWorkspaceSession(
          id = "session-2",
          workflowId = "wf-99",
          correlationId = "corr-xyz",
          status = ParallelSessionStatus.ReadyForReview,
          baseBranch = "main",
          worktreeRuns = List(
            WorktreeRunRef(
              stepId = "step-a",
              workspaceRunId = "run-a",
              agentName = "opencode",
              branch = "parallel/step-a-xyz",
              status = WorktreeRunStatus.Completed,
              summary = Some("Added tests"),
              diffStats = Some(DiffStats(filesChanged = 3, linesAdded = 50, linesRemoved = 5)),
            ),
            WorktreeRunRef(
              stepId = "step-b",
              workspaceRunId = "run-b",
              agentName = "gemini",
              branch = "parallel/step-b-xyz",
              status = WorktreeRunStatus.Failed,
              summary = None,
              diffStats = None,
            ),
          ),
          createdAt = now,
          updatedAt = later,
          completedAt = Some(later),
          requestedBy = None,
        )
        assertTrue(session.toJson.fromJson[ParallelWorkspaceSession] == Right(session))
      },
    ),
    suite("ParallelSessionError")(
      test("WorkflowNotFound round-trips through JSON") {
        val err: ParallelSessionError = ParallelSessionError.WorkflowNotFound("wf-42")
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
      test("WorkspaceNotFound round-trips through JSON") {
        val err: ParallelSessionError = ParallelSessionError.WorkspaceNotFound("ws-1")
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
      test("SessionNotFound round-trips through JSON") {
        val err: ParallelSessionError = ParallelSessionError.SessionNotFound("session-99")
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
      test("InsufficientResources round-trips through JSON") {
        val err: ParallelSessionError = ParallelSessionError.InsufficientResources(available = 1, required = 3)
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
      test("AgentAssignmentFailed round-trips through JSON") {
        val err: ParallelSessionError =
          ParallelSessionError.AgentAssignmentFailed(stepId = "step-1", reason = "no capable agent found")
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
      test("WorktreeError round-trips through JSON") {
        val err: ParallelSessionError = ParallelSessionError.WorktreeError("git worktree add failed")
        assertTrue(err.toJson.fromJson[ParallelSessionError] == Right(err))
      },
    ),
  )
