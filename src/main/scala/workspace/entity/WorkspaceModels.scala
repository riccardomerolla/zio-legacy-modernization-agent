package workspace.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

case class Workspace(
  id: String,
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean = true,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum RunStatus derives JsonCodec, Schema:
  case Pending, Running, Completed, Failed

case class WorkspaceRun(
  id: String,
  workspaceId: String,
  issueRef: String,
  agentName: String,
  prompt: String,
  conversationId: String,
  worktreePath: String,
  branchName: String,
  status: RunStatus,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum WorkspaceError:
  case NotFound(id: String)
  case Disabled(id: String)
  case WorktreeError(message: String)
  case AgentNotFound(name: String)
  case RunTimeout(runId: String)
  case PersistenceFailure(cause: Throwable)
