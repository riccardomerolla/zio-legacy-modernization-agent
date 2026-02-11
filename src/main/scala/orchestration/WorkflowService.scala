package orchestration

import java.time.Instant

import zio.*
import zio.json.*

import db.*
import models.*

enum WorkflowServiceError derives JsonCodec:
  case ValidationFailed(errors: List[String])
  case PersistenceFailed(error: PersistenceError)
  case StepsDecodingFailed(workflowName: String, reason: String)

trait WorkflowService:
  def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long]
  def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]]
  def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]]
  def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]]
  def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit]
  def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit]

object WorkflowService:
  def createWorkflow(workflow: WorkflowDefinition): ZIO[WorkflowService, WorkflowServiceError, Long] =
    ZIO.serviceWithZIO[WorkflowService](_.createWorkflow(workflow))

  def getWorkflow(id: Long): ZIO[WorkflowService, WorkflowServiceError, Option[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.getWorkflow(id))

  def getWorkflowByName(name: String): ZIO[WorkflowService, WorkflowServiceError, Option[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.getWorkflowByName(name))

  def listWorkflows: ZIO[WorkflowService, WorkflowServiceError, List[WorkflowDefinition]] =
    ZIO.serviceWithZIO[WorkflowService](_.listWorkflows)

  def updateWorkflow(workflow: WorkflowDefinition): ZIO[WorkflowService, WorkflowServiceError, Unit] =
    ZIO.serviceWithZIO[WorkflowService](_.updateWorkflow(workflow))

  def deleteWorkflow(id: Long): ZIO[WorkflowService, WorkflowServiceError, Unit] =
    ZIO.serviceWithZIO[WorkflowService](_.deleteWorkflow(id))

  val live: ZLayer[MigrationRepository, Nothing, WorkflowService] =
    ZLayer.fromFunction(WorkflowServiceLive.apply)

final case class WorkflowServiceLive(
  repository: MigrationRepository
) extends WorkflowService:
  override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long] =
    for
      validated <- validateWorkflow(workflow)
      now       <- Clock.instant
      id        <- repository
                     .createWorkflow(toRow(validated, now, now))
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
    yield id

  override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
    repository
      .getWorkflow(id)
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(ZIO.foreach(_)(fromRow))

  override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
    repository
      .getWorkflowByName(name)
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(ZIO.foreach(_)(fromRow))

  override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]] =
    repository
      .listWorkflows
      .mapError(WorkflowServiceError.PersistenceFailed.apply)
      .flatMap(rows => ZIO.foreach(rows)(fromRow))

  override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit] =
    for
      validated <- validateWorkflow(workflow)
      id        <- ZIO
                     .fromOption(validated.id)
                     .orElseFail(
                       WorkflowServiceError.ValidationFailed(List("Workflow id is required for update"))
                     )
      existing  <- repository
                     .getWorkflow(id)
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
      previous  <- ZIO
                     .fromOption(existing)
                     .orElseFail(WorkflowServiceError.PersistenceFailed(PersistenceError.NotFound("workflows", id)))
      now       <- Clock.instant
      _         <- repository
                     .updateWorkflow(
                       toRow(
                         validated,
                         previous.createdAt,
                         now,
                       )
                     )
                     .mapError(WorkflowServiceError.PersistenceFailed.apply)
    yield ()

  override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit] =
    repository.deleteWorkflow(id).mapError(WorkflowServiceError.PersistenceFailed.apply)

  private def validateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, WorkflowDefinition] =
    WorkflowValidator
      .validate(workflow)
      .fold(
        errors => ZIO.fail(WorkflowServiceError.ValidationFailed(errors)),
        valid => ZIO.succeed(valid),
      )

  private def fromRow(row: WorkflowRow): IO[WorkflowServiceError, WorkflowDefinition] =
    ZIO
      .fromEither(
        row.steps.fromJson[List[MigrationStep]].left.map(error =>
          WorkflowServiceError.StepsDecodingFailed(row.name, error)
        )
      )
      .map { parsedSteps =>
        WorkflowDefinition(
          id = row.id,
          name = row.name,
          description = row.description,
          steps = parsedSteps,
          isBuiltin = row.isBuiltin,
        )
      }

  private def toRow(workflow: WorkflowDefinition, createdAt: Instant, updatedAt: Instant): WorkflowRow =
    WorkflowRow(
      id = workflow.id,
      name = workflow.name,
      description = workflow.description.filter(_.trim.nonEmpty),
      steps = workflow.steps.toJson,
      isBuiltin = workflow.isBuiltin,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
