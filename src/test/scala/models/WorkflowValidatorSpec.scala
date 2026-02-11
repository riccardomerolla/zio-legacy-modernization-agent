package models

import zio.Scope
import zio.test.*

object WorkflowValidatorSpec extends ZIOSpecDefault:
  private val validWorkflow = WorkflowDefinition(
    id = None,
    name = "Pipeline A",
    description = Some("End-to-end flow"),
    steps = List(
      MigrationStep.Discovery,
      MigrationStep.Analysis,
      MigrationStep.Mapping,
      MigrationStep.Transformation,
      MigrationStep.Validation,
      MigrationStep.Documentation,
    ),
    isBuiltin = false,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkflowValidatorSpec")(
    test("accepts a valid workflow") {
      val result = WorkflowValidator.validate(validWorkflow)
      assertTrue(result == Right(validWorkflow))
    },
    test("trims valid name before returning workflow") {
      val result = WorkflowValidator.validate(validWorkflow.copy(name = "  Pipeline A  "))
      assertTrue(result == Right(validWorkflow))
    },
    test("rejects empty name") {
      val result = WorkflowValidator.validate(validWorkflow.copy(name = "  "))
      assertTrue(
        result == Left(List("Workflow name cannot be empty"))
      )
    },
    test("rejects empty steps") {
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = Nil))
      assertTrue(
        result == Left(List("Workflow steps cannot be empty"))
      )
    },
    test("rejects duplicate steps") {
      val steps  = List(
        MigrationStep.Discovery,
        MigrationStep.Analysis,
        MigrationStep.Analysis,
      )
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Left(List("Duplicate step not allowed: Analysis"))
      )
    },
    test("rejects missing dependencies") {
      val steps  = List(MigrationStep.Analysis)
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Left(List("Analysis requires Discovery to be present"))
      )
    },
    test("rejects invalid dependency ordering") {
      val steps  = List(MigrationStep.Analysis, MigrationStep.Discovery)
      val result = WorkflowValidator.validate(validWorkflow.copy(steps = steps))
      assertTrue(
        result == Left(List("Analysis must appear after Discovery"))
      )
    },
    test("returns multiple validation errors in one pass") {
      val steps  = List(MigrationStep.Transformation, MigrationStep.Mapping, MigrationStep.Mapping)
      val result = WorkflowValidator.validate(validWorkflow.copy(name = " ", steps = steps))
      assertTrue(
        result == Left(
          List(
            "Workflow name cannot be empty",
            "Duplicate step not allowed: Mapping",
            "Transformation requires Analysis to be present",
            "Transformation must appear after Mapping",
            "Mapping requires Analysis to be present",
          )
        )
      )
    },
    test("default workflow is valid") {
      val result = WorkflowValidator.validate(WorkflowDefinition.default)
      assertTrue(result == Right(WorkflowDefinition.default))
    },
  )
