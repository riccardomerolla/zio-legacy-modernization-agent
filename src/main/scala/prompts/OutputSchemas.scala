package prompts

import zio.json.ast.Json
import zio.json.ast.Json.*

import models.ResponseSchema

/** JSON schema definitions for AI structured output
  *
  * Provides both human-readable schema templates (for prompt inclusion) and proper JSON Schema objects (for API-level
  * structured output enforcement via OpenAI response_format, Gemini generationConfig, etc.).
  *
  * The JSON Schema objects match the case classes in the models package exactly.
  */
object OutputSchemas:

  // ─── JSON Schema builder helpers ────────────────────────────────────────────

  private def str: Json                                            = Obj("type" -> Str("string"))
  private def num: Json                                            = Obj("type" -> Str("number"))
  private def int: Json                                            = Obj("type" -> Str("integer"))
  private def bool: Json                                           = Obj("type" -> Str("boolean"))
  private def arr(items: Json): Json                               = Obj("type" -> Str("array"), "items" -> items)
  private def nullable(schema: Json): Json                         =
    schema match
      case Obj(fields) => Obj(fields.appended("nullable" -> Bool(true)))
      case other       => other
  private def obj(props: (String, Json)*)(required: String*): Json =
    Obj(
      "type"                 -> Str("object"),
      "properties"           -> Obj(props.toList.map((k, v) => k -> v)*),
      "required"             -> Arr(required.map(Str(_)).toList*),
      "additionalProperties" -> Bool(false),
    )

  // ─── CobolAnalysis ──────────────────────────────────────────────────────────

  private val fileSchema: Json = obj(
    "path"         -> str,
    "name"         -> str,
    "size"         -> int,
    "lineCount"    -> int,
    "lastModified" -> str,
    "encoding"     -> str,
    "fileType"     -> str,
  )("path", "name", "size", "lineCount", "lastModified", "encoding", "fileType")

  private val divisionsSchema: Json = obj(
    "identification" -> nullable(str),
    "environment"    -> nullable(str),
    "data"           -> nullable(str),
    "procedure"      -> nullable(str),
  )()

  private val variableSchema: Json = obj(
    "name"     -> str,
    "level"    -> int,
    "dataType" -> str,
    "picture"  -> nullable(str),
    "usage"    -> nullable(str),
  )("name", "level", "dataType")

  private val statementSchema: Json = obj(
    "lineNumber"    -> int,
    "statementType" -> str,
    "content"       -> str,
  )("lineNumber", "statementType", "content")

  private val procedureSchema: Json = obj(
    "name"       -> str,
    "paragraphs" -> arr(str),
    "statements" -> arr(statementSchema),
  )("name", "paragraphs", "statements")

  private val complexitySchema: Json = obj(
    "cyclomaticComplexity" -> int,
    "linesOfCode"          -> int,
    "numberOfProcedures"   -> int,
  )("cyclomaticComplexity", "linesOfCode", "numberOfProcedures")

  val cobolAnalysisJsonSchema: Json = obj(
    "file"       -> fileSchema,
    "divisions"  -> divisionsSchema,
    "variables"  -> arr(variableSchema),
    "procedures" -> arr(procedureSchema),
    "copybooks"  -> arr(str),
    "complexity" -> complexitySchema,
  )("file", "divisions", "variables", "procedures", "copybooks", "complexity")

  // ─── DependencyGraph ────────────────────────────────────────────────────────

  private val depNodeSchema: Json = obj(
    "id"         -> str,
    "name"       -> str,
    "nodeType"   -> str,
    "complexity" -> int,
  )("id", "name", "nodeType", "complexity")

  private val depEdgeSchema: Json = obj(
    "from"     -> str,
    "to"       -> str,
    "edgeType" -> str,
  )("from", "to", "edgeType")

  val dependencyGraphJsonSchema: Json = obj(
    "nodes"             -> arr(depNodeSchema),
    "edges"             -> arr(depEdgeSchema),
    "serviceCandidates" -> arr(str),
  )("nodes", "edges", "serviceCandidates")

  // ─── JavaEntity ─────────────────────────────────────────────────────────────

  private val javaFieldSchema: Json = obj(
    "name"        -> str,
    "javaType"    -> str,
    "cobolSource" -> str,
    "annotations" -> arr(str),
  )("name", "javaType", "cobolSource", "annotations")

  val javaEntityJsonSchema: Json = obj(
    "className"   -> str,
    "packageName" -> str,
    "fields"      -> arr(javaFieldSchema),
    "annotations" -> arr(str),
    "sourceCode"  -> str,
  )("className", "packageName", "fields", "annotations", "sourceCode")

  // ─── JavaService ────────────────────────────────────────────────────────────

  private val javaParamSchema: Json = obj(
    "name"     -> str,
    "javaType" -> str,
  )("name", "javaType")

  private val javaMethodSchema: Json = obj(
    "name"       -> str,
    "returnType" -> str,
    "parameters" -> arr(javaParamSchema),
    "body"       -> str,
  )("name", "returnType", "parameters", "body")

  val javaServiceJsonSchema: Json = obj(
    "name"    -> str,
    "methods" -> arr(javaMethodSchema),
  )("name", "methods")

  // ─── JavaController ─────────────────────────────────────────────────────────

  private val endpointSchema: Json = obj(
    "path"       -> str,
    "method"     -> str,
    "methodName" -> str,
  )("path", "method", "methodName")

  val javaControllerJsonSchema: Json = obj(
    "name"      -> str,
    "basePath"  -> str,
    "endpoints" -> arr(endpointSchema),
  )("name", "basePath", "endpoints")

  // ─── SemanticValidation ─────────────────────────────────────────────────────

  private val validationIssueSchema: Json = obj(
    "severity"   -> str,
    "category"   -> str,
    "message"    -> str,
    "file"       -> nullable(str),
    "line"       -> nullable(int),
    "suggestion" -> nullable(str),
  )("severity", "category", "message")

  val semanticValidationJsonSchema: Json = obj(
    "businessLogicPreserved" -> bool,
    "confidence"             -> num,
    "summary"                -> str,
    "issues"                 -> arr(validationIssueSchema),
  )("businessLogicPreserved", "confidence", "summary", "issues")

  // ─── BusinessLogicExtraction ────────────────────────────────────────────────

  private val useCaseSchema: Json = obj(
    "name"        -> str,
    "trigger"     -> str,
    "description" -> str,
    "keySteps"    -> arr(str),
  )("name", "trigger", "description", "keySteps")

  private val businessRuleSchema: Json = obj(
    "category"    -> str,
    "description" -> str,
    "condition"   -> nullable(str),
    "errorCode"   -> nullable(str),
    "suggestion"  -> nullable(str),
  )("category", "description")

  val businessLogicExtractionJsonSchema: Json = obj(
    "fileName"        -> str,
    "businessPurpose" -> str,
    "useCases"        -> arr(useCaseSchema),
    "rules"           -> arr(businessRuleSchema),
    "summary"         -> str,
  )("fileName", "businessPurpose", "useCases", "rules", "summary")

  // ─── MigrationDocumentation ─────────────────────────────────────────────────

  private val diagramSchema: Json = obj(
    "name"        -> str,
    "diagramType" -> str,
    "content"     -> str,
  )("name", "diagramType", "content")

  val migrationDocumentationJsonSchema: Json = obj(
    "generatedAt"          -> str,
    "summaryReport"        -> str,
    "designDocument"       -> str,
    "apiDocumentation"     -> str,
    "dataMappingReference" -> str,
    "deploymentGuide"      -> str,
    "diagrams"             -> arr(diagramSchema),
  )(
    "generatedAt",
    "summaryReport",
    "designDocument",
    "apiDocumentation",
    "dataMappingReference",
    "deploymentGuide",
    "diagrams",
  )

  // ─── ResponseSchema lookup ──────────────────────────────────────────────────

  val jsonSchemaMap: Map[String, ResponseSchema] = Map(
    "CobolAnalysis"           -> ResponseSchema("CobolAnalysis", cobolAnalysisJsonSchema),
    "DependencyGraph"         -> ResponseSchema("DependencyGraph", dependencyGraphJsonSchema),
    "JavaEntity"              -> ResponseSchema("JavaEntity", javaEntityJsonSchema),
    "JavaService"             -> ResponseSchema("JavaService", javaServiceJsonSchema),
    "JavaController"          -> ResponseSchema("JavaController", javaControllerJsonSchema),
    "SemanticValidation"      -> ResponseSchema("SemanticValidation", semanticValidationJsonSchema),
    "BusinessLogicExtraction" -> ResponseSchema("BusinessLogicExtraction", businessLogicExtractionJsonSchema),
    "MigrationDocumentation"  -> ResponseSchema("MigrationDocumentation", migrationDocumentationJsonSchema),
  )

  def getResponseSchema(className: String): Option[ResponseSchema] =
    jsonSchemaMap.get(className)

  // ─── Human-readable schemas (kept for prompt text and Gemini CLI fallback) ─

  val cobolAnalysis: String =
    """|{
       |  "file": { "path": "string", "name": "string", "size": "number", "lineCount": "number", "lastModified": "string (ISO-8601)", "encoding": "string", "fileType": "string (Program | Copybook | JCL)" },
       |  "divisions": { "identification": "string?", "environment": "string?", "data": "string?", "procedure": "string?" },
       |  "variables": [{ "name": "string", "level": "number", "dataType": "string", "picture": "string?", "usage": "string?" }],
       |  "procedures": [{ "name": "string", "paragraphs": ["string"], "statements": [{ "lineNumber": "number", "statementType": "string", "content": "string" }] }],
       |  "copybooks": ["string"],
       |  "complexity": { "cyclomaticComplexity": "number", "linesOfCode": "number", "numberOfProcedures": "number" }
       |}""".stripMargin

  val dependencyGraph: String =
    """|{
       |  "nodes": [{ "id": "string", "name": "string", "nodeType": "string (Program | Copybook | SharedService)", "complexity": "number" }],
       |  "edges": [{ "from": "string", "to": "string", "edgeType": "string (Includes | Calls | Uses)" }],
       |  "serviceCandidates": ["string"]
       |}""".stripMargin

  val javaEntity: String =
    """|{
       |  "className": "string", "packageName": "string",
       |  "fields": [{ "name": "string", "javaType": "string", "cobolSource": "string", "annotations": ["string"] }],
       |  "annotations": ["string"], "sourceCode": "string"
       |}""".stripMargin

  val javaService: String =
    """|{
       |  "name": "string",
       |  "methods": [{ "name": "string", "returnType": "string", "parameters": [{ "name": "string", "javaType": "string" }], "body": "string" }]
       |}""".stripMargin

  val javaController: String =
    """|{
       |  "name": "string", "basePath": "string",
       |  "endpoints": [{ "path": "string", "method": "string (GET | POST | PUT | DELETE | PATCH)", "methodName": "string" }]
       |}""".stripMargin

  val semanticValidation: String =
    """|{
       |  "businessLogicPreserved": "boolean", "confidence": "number (0.0-1.0)", "summary": "string",
       |  "issues": [{ "severity": "string (ERROR | WARNING | INFO)", "category": "string", "message": "string", "file": "string?", "line": "number?", "suggestion": "string?" }]
       |}""".stripMargin

  val validationReport: String =
    """|{
       |  "projectName": "string", "validatedAt": "string (ISO-8601)",
       |  "compileResult": { "success": "boolean", "exitCode": "number", "output": "string" },
       |  "coverageMetrics": { "variablesCovered": "number", "proceduresCovered": "number", "fileSectionCovered": "number", "unmappedItems": ["string"] },
       |  "issues": [{ "severity": "string", "category": "string", "message": "string", "file": "string?", "line": "number?", "suggestion": "string?" }],
       |  "semanticValidation": { "businessLogicPreserved": "boolean", "confidence": "number", "summary": "string", "issues": [] },
       |  "overallStatus": "string (Passed | PassedWithWarnings | Failed)"
       |}""".stripMargin

  val testResults: String =
    """{ "totalTests": "number", "passed": "number", "failed": "number" }"""

  val validationIssue: String =
    """{ "severity": "string", "category": "string", "message": "string", "file": "string?", "line": "number?", "suggestion": "string?" }"""

  val validationStatus: String =
    """{ "value": "string (Passed | PassedWithWarnings | Failed)" }"""

  val fileInventory: String =
    """|{
       |  "discoveredAt": "string (ISO-8601)", "sourceDirectory": "string",
       |  "files": [{ "path": "string", "name": "string", "size": "number", "lineCount": "number", "lastModified": "string", "encoding": "string", "fileType": "string" }],
       |  "summary": { "totalFiles": "number", "programFiles": "number", "copybooks": "number", "jclFiles": "number", "totalLines": "number", "totalBytes": "number" }
       |}""".stripMargin

  val migrationDocumentation: String =
    """|{
       |  "generatedAt": "string (ISO-8601)", "summaryReport": "string", "designDocument": "string",
       |  "apiDocumentation": "string", "dataMappingReference": "string", "deploymentGuide": "string",
       |  "diagrams": [{ "name": "string", "diagramType": "string (Mermaid | PlantUML)", "content": "string" }]
       |}""".stripMargin

  val businessLogicExtraction: String =
    """|{
       |  "fileName": "string", "businessPurpose": "string",
       |  "useCases": [{ "name": "string", "trigger": "string", "description": "string", "keySteps": ["string"] }],
       |  "rules": [{ "category": "string", "description": "string", "condition": "string?", "errorCode": "string?", "suggestion": "string?" }],
       |  "summary": "string"
       |}""".stripMargin

  val schemaMap: Map[String, String] = Map(
    "CobolAnalysis"           -> cobolAnalysis,
    "DependencyGraph"         -> dependencyGraph,
    "JavaEntity"              -> javaEntity,
    "JavaService"             -> javaService,
    "JavaController"          -> javaController,
    "SemanticValidation"      -> semanticValidation,
    "ValidationIssue"         -> validationIssue,
    "ValidationStatus"        -> validationStatus,
    "TestResults"             -> testResults,
    "ValidationReport"        -> validationReport,
    "MigrationDocumentation"  -> migrationDocumentation,
    "FileInventory"           -> fileInventory,
    "BusinessLogicExtraction" -> businessLogicExtraction,
  )

  def getSchema(className: String): String =
    schemaMap.getOrElse(
      className,
      s"""Schema for $className not found. Valid schemas: ${schemaMap.keys.mkString(", ")}""",
    )
