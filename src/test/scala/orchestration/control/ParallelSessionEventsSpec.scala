package orchestration.control

import java.time.Instant

import zio.json.*
import zio.test.*

import orchestration.entity.DiffStats

object ParallelSessionEventsSpec extends ZIOSpecDefault:
  private val now = Instant.parse("2026-03-04T10:00:00Z")

  def spec: Spec[Environment, Any] = suite("ParallelSessionEventsSpec")(
    test("SessionStarted round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.SessionStarted(
        sessionId = "session-1",
        workflowId = "wf-42",
        worktreeCount = 3,
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("WorktreeAgentStarted round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.WorktreeAgentStarted(
        sessionId = "session-1",
        stepId = "step-1",
        agentName = "claude",
        branch = "parallel/feature-abc123",
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("WorktreeAgentProgress round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.WorktreeAgentProgress(
        sessionId = "session-1",
        stepId = "step-1",
        agentName = "claude",
        message = "Compiling...",
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("WorktreeAgentCompleted round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.WorktreeAgentCompleted(
        sessionId = "session-1",
        stepId = "step-1",
        agentName = "claude",
        branch = "parallel/feature-abc123",
        diffStats = DiffStats(filesChanged = 8, linesAdded = 142, linesRemoved = 12),
        summary = "Implemented auth module with JWT support",
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("WorktreeAgentFailed round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.WorktreeAgentFailed(
        sessionId = "session-1",
        stepId = "step-2",
        agentName = "gemini",
        reason = "compilation error in UserService.scala",
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("SessionReadyForReview round-trips through JSON") {
      val event: ParallelSessionEvent = ParallelSessionEvent.SessionReadyForReview(
        sessionId = "session-1",
        succeeded = 2,
        failed = 1,
        branches = List("parallel/feature-abc123", "parallel/tests-abc123"),
        occurredAt = now,
      )
      assertTrue(event.toJson.fromJson[ParallelSessionEvent] == Right(event))
    },
    test("all events carry sessionId and occurredAt") {
      val events: List[ParallelSessionEvent] = List(
        ParallelSessionEvent.SessionStarted("s1", "wf-1", 2, now),
        ParallelSessionEvent.WorktreeAgentStarted("s1", "step-1", "claude", "branch-1", now),
        ParallelSessionEvent.WorktreeAgentProgress("s1", "step-1", "claude", "Running tests", now),
        ParallelSessionEvent.WorktreeAgentCompleted(
          "s1",
          "step-1",
          "claude",
          "branch-1",
          DiffStats(1, 10, 2),
          "done",
          now,
        ),
        ParallelSessionEvent.WorktreeAgentFailed("s1", "step-2", "gemini", "error", now),
        ParallelSessionEvent.SessionReadyForReview("s1", 1, 1, List("branch-1"), now),
      )
      assertTrue(
        events.forall(_.sessionId == "s1"),
        events.forall(_.occurredAt == now),
      )
    },
  )
