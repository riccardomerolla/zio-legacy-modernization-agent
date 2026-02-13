package agents

import java.time.Instant

import zio.test.*

import db.CustomAgentRow
import models.AgentType

object AgentRegistrySpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-02-13T10:00:00Z")

  def spec: Spec[TestEnvironment, Any] = suite("AgentRegistry")(
    test("findByName should be case-insensitive and trim input") {
      val found = AgentRegistry.findByName("  COBOLanalyzer ")
      assertTrue(found.exists(_.name == "cobolAnalyzer"))
    },
    test("allAgents should merge built-in and unique custom agents") {
      val customAgents = List(
        CustomAgentRow(
          name = "customOne",
          displayName = "Zeta Custom",
          description = Some("My custom"),
          systemPrompt = "Prompt",
          tags = Some("alpha, beta"),
          createdAt = now,
          updatedAt = now,
        ),
        // Duplicate by normalized name: only the first row should be kept.
        CustomAgentRow(
          name = " customone ",
          displayName = "Should Be Dropped",
          description = Some("Duplicate"),
          systemPrompt = "Prompt",
          tags = Some("ignored"),
          createdAt = now,
          updatedAt = now,
        ),
        // Built-in name should be filtered out.
        CustomAgentRow(
          name = "cobolAnalyzer",
          displayName = "Conflicting BuiltIn",
          description = None,
          systemPrompt = "Prompt",
          tags = Some("x"),
          createdAt = now,
          updatedAt = now,
        ),
        CustomAgentRow(
          name = "customTwo",
          displayName = "Alpha Custom",
          description = None,
          systemPrompt = "Prompt",
          tags = Some(" gamma , ,delta "),
          createdAt = now,
          updatedAt = now,
        ),
      )

      val all        = AgentRegistry.allAgents(customAgents)
      val customOnly = all.filter(_.agentType == AgentType.Custom)

      assertTrue(
        all.count(_.name == "cobolAnalyzer") == 1,
        customOnly.map(_.displayName) == List("Alpha Custom", "Zeta Custom"),
        customOnly.exists(a =>
          a.name == "customOne" &&
          a.description == "My custom" &&
          a.tags == List("alpha", "beta")
        ),
        customOnly.exists(a =>
          a.name == "customTwo" &&
          a.description == "Custom agent" &&
          a.tags == List("gamma", "delta")
        ),
      )
    },
  )
