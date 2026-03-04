package orchestration.control

import java.time.Instant

import zio.test.*

import gateway.entity.{ GatewayMessageRole, MessageDirection, SessionKey }
import orchestration.entity.DiffStats

object ParallelSessionFormatterSpec extends ZIOSpecDefault:
  private val now        = Instant.parse("2026-03-04T10:00:00Z")
  private val channel    = "telegram"
  private val sessionKey = SessionKey("telegram", "user:alice")

  def spec: Spec[Environment, Any] = suite("ParallelSessionFormatterSpec")(
    test("SessionStarted produces system outbound message with agent count and branches") {
      val event = ParallelSessionEvent.SessionStarted(
        sessionId = "s1",
        workflowId = "wf-42",
        worktreeCount = 3,
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.channelName == channel,
        msg.sessionKey == sessionKey,
        msg.content.contains("3 agents dispatched"),
      )
    },
    test("WorktreeAgentStarted produces message with agent name and branch") {
      val event = ParallelSessionEvent.WorktreeAgentStarted(
        sessionId = "s1",
        stepId = "step-1",
        agentName = "claude",
        branch = "parallel/feature-abc123",
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.content.contains("claude"),
        msg.content.contains("parallel/feature-abc123"),
      )
    },
    test("WorktreeAgentProgress produces message with agent name and progress message") {
      val event = ParallelSessionEvent.WorktreeAgentProgress(
        sessionId = "s1",
        stepId = "step-1",
        agentName = "claude",
        message = "Compiling...",
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.content.contains("claude"),
        msg.content.contains("Compiling..."),
      )
    },
    test("WorktreeAgentCompleted produces message with diff stats") {
      val event = ParallelSessionEvent.WorktreeAgentCompleted(
        sessionId = "s1",
        stepId = "step-1",
        agentName = "claude",
        branch = "parallel/feature-abc123",
        diffStats = DiffStats(filesChanged = 8, linesAdded = 142, linesRemoved = 12),
        summary = "Implemented auth module",
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.content.contains("claude"),
        msg.content.contains("142"),
        msg.content.contains("12"),
        msg.content.contains("8"),
      )
    },
    test("WorktreeAgentFailed produces message with agent name and reason") {
      val event = ParallelSessionEvent.WorktreeAgentFailed(
        sessionId = "s1",
        stepId = "step-2",
        agentName = "gemini",
        reason = "compilation error in UserService.scala",
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.content.contains("gemini"),
        msg.content.contains("compilation error in UserService.scala"),
      )
    },
    test("SessionReadyForReview produces message with succeeded/failed counts and branch list") {
      val event = ParallelSessionEvent.SessionReadyForReview(
        sessionId = "s1",
        succeeded = 2,
        failed = 1,
        branches = List("parallel/feature-abc123", "parallel/tests-abc123"),
        occurredAt = now,
      )
      val msg   = ParallelSessionFormatter.toNormalizedMessage(event, channel, sessionKey)
      assertTrue(
        msg.role == GatewayMessageRole.System,
        msg.direction == MessageDirection.Outbound,
        msg.content.contains("2"),
        msg.content.contains("1"),
        msg.content.contains("parallel/feature-abc123"),
        msg.content.contains("parallel/tests-abc123"),
      )
    },
    test("all events produce messages bound to the given channel and sessionKey") {
      val events: List[ParallelSessionEvent] = List(
        ParallelSessionEvent.SessionStarted("s1", "wf-1", 2, now),
        ParallelSessionEvent.WorktreeAgentStarted("s1", "step-1", "claude", "branch-1", now),
        ParallelSessionEvent.WorktreeAgentProgress("s1", "step-1", "claude", "Running", now),
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
      val msgs                               = events.map(ParallelSessionFormatter.toNormalizedMessage(_, channel, sessionKey))
      assertTrue(
        msgs.forall(_.channelName == channel),
        msgs.forall(_.sessionKey == sessionKey),
        msgs.forall(_.role == GatewayMessageRole.System),
        msgs.forall(_.direction == MessageDirection.Outbound),
      )
    },
  )
