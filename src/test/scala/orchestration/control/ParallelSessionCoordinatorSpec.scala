package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import orchestration.entity.*

object ParallelSessionCoordinatorSpec extends ZIOSpecDefault:
  private val now = Instant.parse("2026-03-04T10:00:00Z")

  def spec: Spec[Environment, Any] = suite("ParallelSessionCoordinatorSpec")(
    suite("DiffStats parsing")(
      test("parses standard git diff --stat output") {
        val output = "8 files changed, 142 insertions(+), 12 deletions(-)"
        assertTrue(ParallelSessionCoordinator.parseDiffStats(output) == Some(DiffStats(8, 142, 12)))
      },
      test("parses singular 'file changed' output") {
        val output = "1 file changed, 5 insertions(+), 3 deletions(-)"
        assertTrue(ParallelSessionCoordinator.parseDiffStats(output) == Some(DiffStats(1, 5, 3)))
      },
      test("parses output embedded in multi-line git output") {
        val output = """|
                        | src/Foo.scala | 10 +++++++++
                        | src/Bar.scala |  5 -----
                        | 2 files changed, 10 insertions(+), 5 deletions(-)
                        |""".stripMargin
        assertTrue(ParallelSessionCoordinator.parseDiffStats(output) == Some(DiffStats(2, 10, 5)))
      },
      test("returns None for unrecognised output") {
        assertTrue(ParallelSessionCoordinator.parseDiffStats("nothing useful") == None)
      },
      test("returns None for empty string") {
        assertTrue(ParallelSessionCoordinator.parseDiffStats("") == None)
      },
    ),
    suite("session state helpers")(
      test("a session with all worktrees completed is done") {
        val session = makeSession(
          List(WorktreeRunStatus.Completed, WorktreeRunStatus.Completed, WorktreeRunStatus.Completed)
        )
        assertTrue(ParallelSessionCoordinator.allWorktreesDone(session))
      },
      test("a session with one failed worktree is done") {
        val session = makeSession(
          List(WorktreeRunStatus.Completed, WorktreeRunStatus.Failed, WorktreeRunStatus.Completed)
        )
        assertTrue(ParallelSessionCoordinator.allWorktreesDone(session))
      },
      test("a session with a running worktree is not done") {
        val session = makeSession(
          List(WorktreeRunStatus.Completed, WorktreeRunStatus.Running, WorktreeRunStatus.Completed)
        )
        assertTrue(!ParallelSessionCoordinator.allWorktreesDone(session))
      },
      test("a session with a pending worktree is not done") {
        val session = makeSession(List(WorktreeRunStatus.Pending))
        assertTrue(!ParallelSessionCoordinator.allWorktreesDone(session))
      },
      test("succeededBranches returns only completed worktrees' branches") {
        val session  = makeSession(
          List(WorktreeRunStatus.Completed, WorktreeRunStatus.Failed, WorktreeRunStatus.Completed)
        )
        val branches = ParallelSessionCoordinator.succeededBranches(session)
        assertTrue(branches.size == 2, branches.forall(_.startsWith("parallel/")))
      },
    ),
  )

  private def makeSession(statuses: List[WorktreeRunStatus]): ParallelWorkspaceSession =
    ParallelWorkspaceSession(
      id = "s1",
      workflowId = "wf-1",
      correlationId = "corr-1",
      status = ParallelSessionStatus.Running,
      baseBranch = "main",
      worktreeRuns = statuses.zipWithIndex.map {
        case (s, i) =>
          WorktreeRunRef(
            stepId = s"step-$i",
            workspaceRunId = s"run-$i",
            agentName = "claude",
            branch = s"parallel/step-$i",
            status = s,
            summary = None,
            diffStats = None,
          )
      },
      createdAt = now,
      updatedAt = now,
      completedAt = None,
      requestedBy = None,
    )
