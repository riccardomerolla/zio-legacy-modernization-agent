package shared.web

import zio.json.*

import agent.entity.Agent
import agent.entity.api.{ AgentActiveRun, AgentMetricsHistoryPoint, AgentMetricsSummary, AgentRunHistoryItem }
import scalatags.Text.all.*

object AgentRegistryView:

  def list(agents: List[Agent], flash: Option[String] = None): String =
    Layout.page("Agent Registry", "/agents/registry")(
      div(cls := "space-y-4")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex items-center justify-between")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Agent Registry"),
              p(cls := "mt-1 text-sm text-slate-300")("First-class event-sourced agents"),
            ),
            a(
              href := "/agents/registry/new",
              cls  := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("New Agent"),
          )
        ),
        flash.map(msg =>
          div(cls := "rounded-md bg-emerald-500/10 border border-emerald-400/30 p-3 text-sm text-emerald-200")(msg)
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 overflow-hidden")(
          if agents.isEmpty then
            div(cls := "px-4 py-8 text-sm text-slate-400")("No agents registered yet.")
          else
            agents.map { agent =>
              div(cls := "border-b border-white/10 px-4 py-3 last:border-b-0")(
                div(cls := "flex items-center justify-between gap-3")(
                  div(
                    a(
                      href := s"/agents/registry/${agent.id.value}",
                      cls  := "text-sm font-semibold text-slate-100 hover:text-indigo-300",
                    )(agent.name),
                    p(cls := "mt-1 text-xs text-slate-400")(agent.description),
                  ),
                  div(cls := "text-xs text-slate-300")(
                    span(
                      cls := (if agent.enabled then "rounded bg-emerald-500/20 px-2 py-1 text-emerald-200"
                              else "rounded bg-slate-700/40 px-2 py-1 text-slate-300")
                    )(if agent.enabled then "enabled" else "disabled"),
                    span(cls := "ml-2 rounded bg-indigo-500/20 px-2 py-1 text-indigo-200")(agent.cliTool),
                  ),
                )
              )
            }
        ),
      )
    )

  def detail(
    agent: Agent,
    metrics: AgentMetricsSummary,
    runs: List[AgentRunHistoryItem],
    activeRuns: List[AgentActiveRun],
    history: List[AgentMetricsHistoryPoint],
    flash: Option[String] = None,
  ): String =
    Layout.page(s"Agent ${agent.name}", "/agents/registry")(
      div(cls := "space-y-4")(
        a(
          href := "/agents/registry",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("← Back to registry"),
        flash.map(msg =>
          div(cls := "rounded-md bg-emerald-500/10 border border-emerald-400/30 p-3 text-sm text-emerald-200")(msg)
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
          h1(cls := "text-xl font-bold text-white")(agent.name),
          p(cls := "mt-1 text-sm text-slate-300")(agent.description),
          div(cls := "mt-3 flex flex-wrap gap-2 text-xs")(
            span(cls := "rounded bg-indigo-500/20 px-2 py-1 text-indigo-200")(s"cli: ${agent.cliTool}"),
            span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"timeout: ${agent.timeout}"),
            span(
              cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200"
            )(s"maxConcurrentRuns: ${agent.maxConcurrentRuns}"),
            agent.dockerMemoryLimit.map(limit =>
              span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"docker memory: $limit")
            ),
            agent.dockerCpuLimit.map(limit =>
              span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"docker cpus: $limit")
            ),
          ),
          div(cls := "mt-3 text-xs text-slate-300")(
            strong("Capabilities: "),
            if agent.capabilities.isEmpty then "none" else agent.capabilities.mkString(", "),
          ),
          div(cls := "mt-4 flex gap-2")(
            a(
              href := s"/agents/registry/${agent.id.value}/edit",
              cls  := "rounded-md border border-indigo-300/30 px-3 py-1.5 text-xs font-semibold text-indigo-200 hover:bg-indigo-500/20",
            )("Edit"),
            tag("form")(method := "post", action := s"/agents/registry/${agent.id.value}/disable")(
              button(
                `type` := "submit",
                cls    := "rounded-md border border-amber-300/30 px-3 py-1.5 text-xs font-semibold text-amber-200 hover:bg-amber-500/20",
              )(if agent.enabled then "Disable" else "Enable")
            ),
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Metrics"),
          div(cls := "mt-2 grid grid-cols-2 gap-2 text-xs text-slate-300 md:grid-cols-5")(
            metricCard("Total", metrics.totalRuns.toString),
            metricCard("Completed", metrics.completedRuns.toString),
            metricCard("Failed", metrics.failedRuns.toString),
            metricCard("Active", metrics.activeRuns.toString),
            metricCard("Success", f"${metrics.successRate * 100}%.1f%%"),
          ),
          div(cls := "mt-2 grid grid-cols-1 gap-2 text-xs text-slate-300 md:grid-cols-4")(
            metricCard("Runs (7d)", metrics.totalRuns7d.toString),
            metricCard("Runs (30d)", metrics.totalRuns30d.toString),
            metricCard("Avg Duration", formatSeconds(metrics.averageDurationSeconds)),
            metricCard("Issues Resolved", metrics.issuesResolvedCount.toString),
          ),
        ),
        div(
          cls                                := "rounded-xl border border-white/10 bg-slate-900/60 p-4",
          attr("data-agent-metrics-history") := "true",
          attr("data-history-points")        := history.toJson,
        )(
          h2(cls := "text-sm font-semibold text-slate-100")("30d Trend"),
          div(cls := "mt-3 grid grid-cols-1 gap-3 md:grid-cols-2")(
            div(
              p(cls := "text-xs text-slate-400")("Success Rate"),
              div(cls := "mt-1 rounded border border-white/10 bg-slate-800/70 p-2")(
                tag("svg")(
                  cls                         := "w-full h-16 text-emerald-300",
                  attr("viewBox")             := "0 0 100 40",
                  attr("preserveAspectRatio") := "none",
                  attr("data-sparkline")      := "success-rate",
                )
              ),
            ),
            div(
              p(cls := "text-xs text-slate-400")("Run Count"),
              div(cls := "mt-1 rounded border border-white/10 bg-slate-800/70 p-2")(
                tag("svg")(
                  cls                         := "w-full h-16 text-indigo-300",
                  attr("viewBox")             := "0 0 100 40",
                  attr("preserveAspectRatio") := "none",
                  attr("data-sparkline")      := "run-count",
                )
              ),
            ),
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Active Runs"),
          if activeRuns.isEmpty then
            p(cls := "mt-2 text-xs text-slate-400")("No active runs.")
          else
            div(cls := "mt-2 space-y-2")(
              activeRuns.map { run =>
                val target =
                  if run.conversationId.trim.nonEmpty then s"/chat/${run.conversationId}"
                  else s"/runs?agent=${agent.name}"
                a(
                  href := target,
                  cls  := "block rounded border border-white/10 bg-slate-800/70 px-3 py-2 text-xs text-slate-300 hover:bg-slate-800",
                )(
                  span(cls := "font-semibold text-slate-100")(run.runId),
                  span(cls := "ml-2")(s"workspace:${run.workspaceId}"),
                  span(cls := "ml-2")(run.status),
                  span(cls := "ml-2 text-slate-400")(run.issueRef),
                )
              }
            ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Run History"),
          if runs.isEmpty then
            p(cls := "mt-2 text-xs text-slate-400")("No runs found for this agent.")
          else
            div(cls := "mt-2 space-y-2")(
              runs.map { run =>
                div(cls := "rounded border border-white/10 bg-slate-800/70 px-3 py-2 text-xs text-slate-300")(
                  span(cls := "font-semibold text-slate-100")(run.runId),
                  span(cls := "ml-2")(s"workspace:${run.workspaceId}"),
                  span(cls := "ml-2")(run.status),
                  span(cls := "ml-2 text-slate-500")(run.updatedAt.toString),
                )
              }
            ),
        ),
        JsResources.inlineModuleScript("/static/client/components/agent-metrics.js"),
      )
    )

  def form(
    title: String,
    formAction: String,
    values: Map[String, String],
  ): String =
    Layout.page(title, "/agents/registry")(
      div(cls := "max-w-3xl space-y-4")(
        a(
          href := "/agents/registry",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("← Back to registry"),
        h1(cls := "text-2xl font-bold text-white")(title),
        tag("form")(
          method := "post",
          action := formAction,
          cls    := "space-y-4 rounded-xl border border-white/10 bg-slate-900/70 p-5",
        )(
          textField("name", "Name", values.getOrElse("name", "")),
          textField("description", "Description", values.getOrElse("description", "")),
          textField("cliTool", "CLI Tool", values.getOrElse("cliTool", "gemini")),
          capabilityEditor(
            fieldName = "capabilities",
            labelText = "Capabilities",
            valueText = values.getOrElse("capabilities", ""),
          ),
          textField("defaultModel", "Default Model", values.getOrElse("defaultModel", "")),
          textAreaField("systemPrompt", "System Prompt", values.getOrElse("systemPrompt", "")),
          textField("maxConcurrentRuns", "Max Concurrent Runs", values.getOrElse("maxConcurrentRuns", "1")),
          textField("timeout", "Timeout ISO-8601 (e.g. PT30M)", values.getOrElse("timeout", "PT30M")),
          textField(
            "dockerMemoryLimit",
            "Docker Memory Limit (optional, e.g. 2g)",
            values.getOrElse("dockerMemoryLimit", ""),
          ),
          textField(
            "dockerCpuLimit",
            "Docker CPU Limit (optional, e.g. 1.5)",
            values.getOrElse("dockerCpuLimit", ""),
          ),
          textAreaField("envVars", "Env Vars (KEY=VALUE per line)", values.getOrElse("envVars", "")),
          div(
            label(cls := "inline-flex items-center gap-2 text-sm text-slate-200")(
              input(
                `type` := "checkbox",
                name   := "enabled",
                if values.getOrElse("enabled", "true") == "true" then checked else (),
              ),
              span("Enabled"),
            )
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )("Save"),
        ),
        JsResources.inlineModuleScript("/static/client/components/capability-tag-editor.js"),
      )
    )

  private def metricCard(labelText: String, value: String): Frag =
    div(cls := "rounded border border-white/10 bg-slate-800/70 px-2 py-2")(
      p(cls := "text-[10px] uppercase tracking-wide text-slate-400")(labelText),
      p(cls := "mt-1 text-sm font-semibold text-slate-100")(value),
    )

  private def textField(nameValue: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := nameValue)(labelText),
      input(
        `type` := "text",
        id     := nameValue,
        name   := nameValue,
        value  := valueText,
        cls    := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
      ),
    )

  private def textAreaField(nameValue: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := nameValue)(labelText),
      textarea(
        id   := nameValue,
        name := nameValue,
        rows := 5,
        cls  := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
      )(valueText),
    )

  private def formatSeconds(seconds: Long): String =
    if seconds <= 0 then "0s"
    else if seconds < 60 then s"${seconds}s"
    else
      val minutes = seconds / 60
      val rem     = seconds % 60
      s"${minutes}m ${rem}s"

  private def capabilityEditor(fieldName: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := s"${fieldName}EditorInput")(labelText),
      div(
        cls                            := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-2",
        attr("data-capability-editor") := "true",
      )(
        input(`type`                    := "hidden", id                                               := fieldName, name := fieldName, value := valueText),
        div(cls                         := "mb-2 flex flex-wrap gap-2", attr("data-capability-chips") := "true"),
        input(
          `type`                        := "text",
          id                            := s"${fieldName}EditorInput",
          cls                           := "w-full rounded border border-white/10 bg-slate-900/80 px-2 py-1.5 text-sm text-slate-100",
          attr("data-capability-input") := "true",
          attr("placeholder")           := "Type capability and press Enter",
        ),
      ),
    )
