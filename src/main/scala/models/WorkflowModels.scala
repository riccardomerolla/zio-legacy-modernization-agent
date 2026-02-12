package models

import zio.json.*

case class WorkflowStepAgent(
  step: MigrationStep,
  agentName: String,
) derives JsonCodec

case class WorkflowDefinition(
  id: Option[Long] = None,
  name: String,
  description: Option[String] = None,
  steps: List[MigrationStep],
  stepAgents: List[WorkflowStepAgent] = Nil,
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
      stepAgents = Nil,
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
    val stepAgentErrors    = invalidStepAgents(workflow.steps, workflow.stepAgents)
    val allErrors          =
      (nameErrors ++ stepPresenceErrors ++ duplicateErrors ++ dependencyErrors ++ stepAgentErrors).distinct

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

  private def invalidStepAgents(steps: List[MigrationStep], stepAgents: List[WorkflowStepAgent]): List[String] =
    val includedSteps        = steps.toSet
    val duplicateAssignments = stepAgents
      .groupBy(_.step)
      .collect {
        case (step, assignments) if assignments.size > 1 =>
          s"Duplicate agent assignment for step ${step.toString}"
      }
      .toList

    val invalidAssignments = stepAgents.flatMap {
      case WorkflowStepAgent(step, _) if !includedSteps.contains(step) =>
        Some(s"Assigned agent for step ${step.toString}, but the step is not selected in workflow")
      case WorkflowStepAgent(_, rawAgent) if rawAgent.trim.isEmpty     =>
        Some("Assigned agent name cannot be empty")
      case _                                                           =>
        None
    }
    (duplicateAssignments ++ invalidAssignments).distinct
