package taskrun.entity

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.json.*

/** Metadata for workspace associated with a task run */
case class WorkspaceMetadata(
  runId: String,
  workspaceRoot: Path,
  stateDir: Path,
  reportsDir: Path,
  outputDir: Path,
  tempDir: Path,
  createdAt: Instant,
) derives JsonCodec

case class TaskError(
  stepName: String,
  message: String,
  timestamp: Instant,
) derives JsonCodec

enum TaskStatus derives JsonCodec:
  case Idle, Running, Paused, Done, Failed

case class TaskState(
  runId: String,
  startedAt: Instant,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  artifacts: Map[String, String],
  errors: List[TaskError],
  config: shared.entity.GatewayConfig,
  workspace: Option[WorkspaceMetadata],
  status: TaskStatus,
  lastCheckpoint: Instant,
  taskRunId: Option[String] = None,
  currentStepName: Option[String] = None,
) derives JsonCodec

case class Checkpoint(
  runId: String,
  step: String,
  createdAt: Instant,
  artifactPaths: Map[String, Path],
  checksum: String,
) derives JsonCodec

case class CheckpointSnapshot(
  checkpoint: Checkpoint,
  state: TaskState,
) derives JsonCodec

case class TaskRunSummary(
  runId: String,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  errorCount: Int = 0,
  taskRunId: Option[String] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
  startedAt: Instant = Instant.EPOCH,
  updatedAt: Instant = Instant.EPOCH,
) derives JsonCodec

given JsonCodec[Path] = JsonCodec[String].transform(
  str => Paths.get(str),
  path => path.toString,
)

given JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)
