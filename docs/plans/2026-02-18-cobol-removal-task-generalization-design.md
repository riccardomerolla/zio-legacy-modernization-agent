# Design: Remove COBOL/Migration Code & Generalize to Task-Centric Architecture

**Date:** 2026-02-18
**Status:** Approved

## Context

The project is pivoting from a COBOL-to-Java migration tool to a self-hosted AI gateway that connects
messaging apps (Telegram, Discord, WhatsApp, iMessage, and more) to AI coding agents, workflows, and
agentic tasks. The core infrastructure (workflow engine, agent orchestration, run tracking, graph/report
views) is reusable — but needs to be stripped of all COBOL/migration-specific naming, models, prompts,
and CLI commands.

## Goal

Remove all COBOL/migration-specific code while **generalizing** the reusable infrastructure to a
**Task-centric domain model** that fits the new gateway/assistant use case.

## Domain Vocabulary Shift

| Old (COBOL migration)                  | New (Gateway / Task-centric)             |
|----------------------------------------|------------------------------------------|
| `MigrationRun`                         | `Task`                                   |
| `MigrationStep`                        | `TaskStep` (dynamic string label)        |
| `MigrationOrchestrator`               | `TaskOrchestrator`                       |
| `MigrationRepository`                 | `TaskRepository`                         |
| `WorkflowRun`                          | `TaskRun`                                |
| `ProgressUpdate`                       | `StepProgress`                           |
| `RunsController` / `RunsView`          | `TasksController` / `TasksView`          |
| `AnalysisController` / `AnalysisView`  | `ReportsController` / `ReportsView`      |
| `GraphController` / `GraphView`        | `GraphController` / `GraphView` (kept)   |
| `MigrationStep.Discovery/Analysis/...` | Removed — steps are dynamic per-workflow |

## Issues

### Issue 1 — Delete pure COBOL artifacts

Delete files with zero reuse value. No generalization, pure removal.

**Files to delete:**
- `src/main/scala/agents/`: CobolDiscoveryAgent, CobolAnalyzerAgent, BusinessLogicExtractorAgent,
  DependencyMapperAgent, JavaTransformerAgent, ValidationAgent, DocumentationAgent
- `src/main/scala/prompts/`: CobolAnalyzerPrompts, BusinessLogicExtractorPrompts,
  DependencyMapperPrompts, JavaTransformerPrompts, ValidationPrompts, DocumentationPrompts, OutputSchemas
- `src/main/scala/models/`: DiscoveryModels, AnalysisModels, MappingModels, TransformationModels,
  ValidationModels, DocumentationModels
- `workspace/cobol-source/` directory
- `reports/` directory
- All migration-specific test files (9 files under `src/test/scala/agents/` and `orchestration/` and `db/`)
- Remove `proleap-cobol-parser` dependency from `build.sbt`

### Issue 2 — Rename domain: `Migration*` → `Task*`

Pure rename, no behavior change. Keeps the project compilable.

- `MigrationOrchestrator` → `TaskOrchestrator`
- `MigrationRepository` → `TaskRepository`
- `MigrationStep` enum → `TaskStep` (becomes a `String` label, dynamic per workflow)
- `MigrationRunRow` → `TaskRunRow` in `Schema.scala`
- DB table: `migration_runs` → `task_runs` (SQLite migration script)
- Update all references in `ApplicationDI`, `RunsController`, `WebServer`

### Issue 3 — Generalize DB schema: remove COBOL-specific tables

- Drop tables: `cobol_files`, `cobol_analyses`, `dependencies`, `phase_progress`
- Keep: `task_runs`, `chat_messages`, `activity_events`, `workflows`, `settings`
- Add: `task_reports` table — stores markdown/mermaid output per step
  - columns: `id`, `task_run_id`, `step_name`, `report_type` (markdown|graph), `content`, `created_at`
- Add: `task_artifacts` table — generic key-value store for step outputs
  - columns: `id`, `task_run_id`, `step_name`, `key`, `value`, `created_at`
- Remove `MigrationRepository` file (after rename in Issue 2, now `TaskRepository`)
- Remove `ResultPersister` (COBOL-specific step result persistence)

### Issue 4 — Generalize `RunsController`/`RunsView` → `TasksController`/`TasksView`

- Rename controller and view files
- Remove hardcoded `defaultWorkflowSteps` list (Discovery, Analysis, Mapping, Transformation,
  Validation, Documentation)
- Tasks are created with a name + dynamic step list from the workflow definition
- Routes: `GET /tasks`, `GET /tasks/:id`, `POST /tasks`
- View shows task name, status, step progress (from `task_runs` + step metadata)

### Issue 5 — Generalize `AnalysisController`/`AnalysisView` → `ReportsController`/`ReportsView`

Was: shows COBOL file analyses tied to a migration run.
Becomes: generic markdown report viewer for any `TaskStep` output.

- Reads from `task_reports` table instead of `cobol_analyses`
- Renders step reports as markdown (with syntax highlighting) and mermaid diagrams
- Routes: `GET /reports?taskId=:id`, `GET /reports/:reportId`
- View renders report content with marked.js (markdown) and mermaid.js (graphs)

### Issue 6 — Generalize `GraphController`/`GraphView`

Was: renders COBOL dependency graph hardcoded to `getDependenciesByRun`.
Becomes: renders any mermaid graph stored as a `task_report` with `report_type=graph`.

- Reads from `task_reports` table filtered by `report_type = "graph"`
- Route stays `GET /graph?taskId=:id` but source changes
- Removes `DependencyRow` / `getDependenciesByRun` dependency

### Issue 7 — Refactor `Main.scala` + CLI + `ApplicationDI`

- Remove all migration CLI commands: `migrate`, `step`, `list-runs`, `discovery`, `analysis`,
  `mapping`, `transformation`, `validation`, `documentation`
- Keep only: `serve` command with `--port`, `--host`, `--db` options
- Remove migration-specific CLI options: `sourceDirOpt`, `outputDirOpt`, `discoveryMaxDepthOpt`, etc.
- Clean up `ApplicationDI`: remove all COBOL agent wiring, `MigrationRepository`, `ProgressTracker`
  migration-specific layers
- Rename project in `build.sbt`: `zio-legacy-modernization-agent` → `openclaw-gateway`

### Issue 8 — Generalize `StateService`, `HealthMonitor`, `ProgressTracker`

- `StateService`: remove `MigrationState`, replace with `TaskState` (current task, step, status)
- `HealthMonitor`: remove migration-status checks, generalize to task/gateway health metrics
- `ProgressTracker`: generalize from hardcoded migration phases to generic `TaskStep` progress events
- `models/StateModels.scala`: remove migration-specific state types, add generic task state types
- `models/ProgressModels.scala`: generalize to task/step progress

## Sequencing Rationale

Issues are ordered so that each one leaves the codebase compilable:
1. Delete leaf files that nothing else depends on
2. Rename at the model/orchestration layer
3. Fix the DB layer to match new names
4. Fix controllers/views bottom-up (Tasks, Reports, Graph)
5. Fix the wiring (Main, DI) last since it references everything
6. Generalize cross-cutting services (State, Health, Progress)
