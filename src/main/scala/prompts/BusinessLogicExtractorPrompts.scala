package prompts

import models.CobolAnalysis

/** Prompt templates for business logic extraction from COBOL analysis.
  */
object BusinessLogicExtractorPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are a senior business analyst reverse-engineering COBOL systems.
      |Extract WHAT the business does, not low-level code mechanics.
      |
      |CRITICAL REQUIREMENTS:
      |- Respond with valid JSON only (no markdown, no commentary)
      |- Keep terminology business-friendly and concise
      |- Include concrete use cases and validation/business rules found in the source
      |- Do not invent operations that are not supported by the provided analysis
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  def extractBusinessLogic(analysis: CobolAnalysis): String =
    val procedures =
      if analysis.procedures.isEmpty then "- (none)"
      else analysis.procedures.map(p => s"- ${p.name}").mkString("\n")

    val variables =
      if analysis.variables.isEmpty then "- (none)"
      else analysis.variables.take(100).map(v => s"- ${v.name} (${v.dataType})").mkString("\n")

    val copybooks =
      if analysis.copybooks.isEmpty then "- (none)"
      else analysis.copybooks.map(cb => s"- $cb").mkString("\n")

    val procedureDivision = analysis.divisions.procedure.getOrElse("")

    s"""$systemPrompt
       |
       |Analyze this COBOL program summary and extract business logic.
       |
       |FILE: ${analysis.file.name}
       |
       |PROGRAM STRUCTURE:
       |- Procedures (${analysis.procedures.size}):
       |$procedures
       |- Variables (${analysis.variables.size}):
       |$variables
       |- Copybooks (${analysis.copybooks.size}):
       |$copybooks
       |- Complexity: cyclomatic=${analysis.complexity.cyclomaticComplexity}, loc=${analysis.complexity.linesOfCode}
       |
       |PROCEDURE DIVISION (raw snippet):
       |```cobol
       |$procedureDivision
       |```
       |
       |${PromptHelpers.schemaReference("BusinessLogicExtraction")}
       |
       |Required output content:
       |1. businessPurpose: 1-2 sentences.
       |2. useCases: list concrete business operations with trigger, description, and key steps.
       |3. rules: validations/constraints/authorizations/data integrity rules.
       |4. summary: concise executive summary.
       |
       |Return a single JSON object using this exact shape:
       |{
       |  "fileName": "${analysis.file.name}",
       |  "businessPurpose": "...",
       |  "useCases": [
       |    {
       |      "name": "...",
       |      "trigger": "...",
       |      "description": "...",
       |      "keySteps": ["...", "..."]
       |    }
       |  ],
       |  "rules": [
       |    {
       |      "category": "DataValidation | BusinessLogic | Authorization | DataIntegrity | Other",
       |      "description": "...",
       |      "condition": "...",
       |      "errorCode": "...",
       |      "suggestion": "..."
       |    }
       |  ],
       |  "summary": "..."
       |}
       |
       |Respond with JSON only.
       |""".stripMargin
