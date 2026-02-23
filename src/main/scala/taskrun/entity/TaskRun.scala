package taskrun.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ArtifactId, ReportId, TaskRunId, WorkflowId }

enum TaskRunState derives JsonCodec, Schema:
  case Pending(createdAt: Instant)
  case Running(startedAt: Instant, currentPhase: String)
  case Completed(startedAt: Instant, completedAt: Instant, summary: String)
  case Failed(startedAt: Instant, failedAt: Instant, errorMessage: String)
  case Cancelled(cancelledAt: Instant, reason: String)

final case class TaskReport(
  id: ReportId,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec, Schema

final case class TaskArtifact(
  id: ArtifactId,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec, Schema

final case class TaskRun(
  id: TaskRunId,
  workflowId: WorkflowId,
  state: TaskRunState,
  agentName: String,
  source: String,
  reports: List[TaskReport],
  artifacts: List[TaskArtifact],
) derives JsonCodec, Schema
