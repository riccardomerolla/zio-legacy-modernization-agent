package models

import zio.json.*

case class WorkflowDefinition(
  id: Option[Long] = None,
  name: String,
  description: Option[String] = None,
  steps: List[MigrationStep],
  isBuiltin: Boolean,
) derives JsonCodec

object WorkflowDefinition:
  val default: WorkflowDefinition =
    WorkflowDefinition(
      id = None,
      name = "Default Workflow",
      description = Some("Built-in end-to-end migration workflow"),
      steps = List(
        MigrationStep.Discovery,
        MigrationStep.Analysis,
        MigrationStep.Mapping,
        MigrationStep.Transformation,
        MigrationStep.Validation,
        MigrationStep.Documentation,
      ),
      isBuiltin = true,
    )

object WorkflowValidator:
  private val dependencies: Map[MigrationStep, List[MigrationStep]] = Map(
    MigrationStep.Analysis       -> List(MigrationStep.Discovery),
    MigrationStep.Mapping        -> List(MigrationStep.Analysis),
    MigrationStep.Transformation -> List(MigrationStep.Analysis, MigrationStep.Mapping),
    MigrationStep.Validation     -> List(MigrationStep.Transformation, MigrationStep.Analysis),
    MigrationStep.Documentation  -> List(MigrationStep.Discovery),
  )

  def validate(workflow: WorkflowDefinition): Either[List[String], WorkflowDefinition] =
    val normalizedName     = workflow.name.trim
    val nameErrors         =
      if normalizedName.nonEmpty then Nil
      else List("Workflow name cannot be empty")
    val stepPresenceErrors =
      if workflow.steps.nonEmpty then Nil
      else List("Workflow steps cannot be empty")
    val duplicateErrors    = duplicateStepErrors(workflow.steps)
    val dependencyErrors   = dependencyOrderingErrors(workflow.steps)
    val allErrors          = (nameErrors ++ stepPresenceErrors ++ duplicateErrors ++ dependencyErrors).distinct

    if allErrors.isEmpty then Right(workflow.copy(name = normalizedName))
    else Left(allErrors)

  private def duplicateStepErrors(steps: List[MigrationStep]): List[String] =
    steps
      .groupBy(identity)
      .collect { case (step, instances) if instances.size > 1 => s"Duplicate step not allowed: ${step.toString}" }
      .toList
      .sorted

  private def dependencyOrderingErrors(steps: List[MigrationStep]): List[String] =
    val positions = steps.zipWithIndex.toMap
    steps.zipWithIndex.flatMap {
      case (step, stepIndex) =>
        dependencies.getOrElse(step, Nil).flatMap { requiredStep =>
          positions.get(requiredStep) match
            case None                                             =>
              Some(s"${step.toString} requires ${requiredStep.toString} to be present")
            case Some(requiredIndex) if requiredIndex > stepIndex =>
              Some(s"${step.toString} must appear after ${requiredStep.toString}")
            case _                                                =>
              None
        }
    }.distinct
