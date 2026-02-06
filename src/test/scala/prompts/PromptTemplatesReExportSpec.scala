package prompts

import zio.test.*

/** Tests for PromptTemplates re-export wrappers (Schemas and Helpers objects).
  *
  * These tests exercise the re-exported methods via the PromptTemplates facade to ensure scoverage captures coverage on
  * the re-export delegates.
  */
object PromptTemplatesReExportSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("PromptTemplatesReExportSpec")(
    suite("PromptTemplates.Schemas re-exports")(
      test("cobolAnalysis schema is accessible") {
        val schema = PromptTemplates.Schemas.cobolAnalysis
        assertTrue(schema.contains("file"), schema.contains("divisions"))
      },
      test("dependencyGraph schema is accessible") {
        val schema = PromptTemplates.Schemas.dependencyGraph
        assertTrue(schema.contains("nodes"), schema.contains("edges"))
      },
      test("javaEntity schema is accessible") {
        val schema = PromptTemplates.Schemas.javaEntity
        assertTrue(schema.contains("className"), schema.contains("fields"))
      },
      test("javaService schema is accessible") {
        val schema = PromptTemplates.Schemas.javaService
        assertTrue(schema.contains("name"), schema.contains("methods"))
      },
      test("javaController schema is accessible") {
        val schema = PromptTemplates.Schemas.javaController
        assertTrue(schema.contains("basePath"), schema.contains("endpoints"))
      },
      test("validationReport schema is accessible") {
        val schema = PromptTemplates.Schemas.validationReport
        assertTrue(schema.contains("compileResult"), schema.contains("overallStatus"))
      },
      test("migrationDocumentation schema is accessible") {
        val schema = PromptTemplates.Schemas.migrationDocumentation
        assertTrue(schema.contains("summaryReport"), schema.contains("diagrams"))
      },
      test("schemaMap contains all schemas") {
        val map = PromptTemplates.Schemas.schemaMap
        assertTrue(
          map.contains("CobolAnalysis"),
          map.contains("DependencyGraph"),
          map.contains("JavaEntity"),
        )
      },
      test("getSchema returns schema for known type") {
        val schema = PromptTemplates.Schemas.getSchema("JavaService")
        assertTrue(schema.contains("methods"))
      },
      test("getSchema returns error for unknown type") {
        val schema = PromptTemplates.Schemas.getSchema("Unknown")
        assertTrue(schema.contains("not found"))
      },
    ),
    suite("PromptTemplates.Helpers re-exports")(
      test("formatCobolCode wraps in cobol block") {
        val result = PromptTemplates.Helpers.formatCobolCode("STOP RUN.")
        assertTrue(result.contains("```cobol"), result.contains("STOP RUN."))
      },
      test("schemaReference generates instructions") {
        val result = PromptTemplates.Helpers.schemaReference("TestClass")
        assertTrue(result.contains("TestClass"), result.contains("OUTPUT FORMAT"))
      },
      test("chunkByDivision extracts divisions") {
        val code   = "IDENTIFICATION DIVISION.\nPROGRAM-ID. TEST.\nPROCEDURE DIVISION.\nMAIN.\n    STOP RUN."
        val chunks = PromptTemplates.Helpers.chunkByDivision(code)
        assertTrue(chunks.contains("IDENTIFICATION"))
      },
      test("validationRules formats fields") {
        val result = PromptTemplates.Helpers.validationRules(List("field1"))
        assertTrue(result.contains("field1 is REQUIRED"))
      },
      test("fewShotExample formats correctly") {
        val result = PromptTemplates.Helpers.fewShotExample("desc", "input", "output")
        assertTrue(result.contains("EXAMPLE: desc"))
      },
      test("estimateTokens returns estimate") {
        val result = PromptTemplates.Helpers.estimateTokens("test" * 100)
        assertTrue(result == 100)
      },
      test("shouldChunk returns false for small text") {
        assertTrue(!PromptTemplates.Helpers.shouldChunk("small"))
      },
    ),
  )
