package issues.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }

enum IssueState derives JsonCodec, Schema:
  case Open(createdAt: Instant)
  case Assigned(agent: AgentId, assignedAt: Instant)
  case InProgress(agent: AgentId, startedAt: Instant)
  case Completed(agent: AgentId, completedAt: Instant, result: String)
  case Failed(agent: AgentId, failedAt: Instant, errorMessage: String)
  case Skipped(skippedAt: Instant, reason: String)

final case class AgentIssue(
  id: IssueId,
  runId: Option[TaskRunId],
  conversationId: Option[ConversationId],
  title: String,
  description: String,
  issueType: String,
  priority: String,
  state: IssueState,
  tags: List[String],
  contextPath: String,
  sourceFolder: String,
) derives JsonCodec, Schema
