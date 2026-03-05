# App Registry — External CLI Apps Integration

**Date:** 2026-03-05
**Status:** Approved

## Overview

Introduce `App` as a first-class entity in llm4zio-gateway, enabling external CLI tools (GitHub CLI, Google Workspace CLI, gcloud, etc.) to be ergonomically integrated into agent tool-calling loops, workflow pipeline steps, and MCP exposure.

## Motivation

Agents and workflows need to interact with external services. CLI tools provide a universal, well-maintained interface to these services. Rather than building custom integrations for each service, we treat CLIs as a pluggable "app" layer that bridges into our existing `Tool` and `WorkflowNode` abstractions.

## Approach: App Registry (First-Class App Abstraction)

An `App` groups related CLI commands, manages lifecycle (install check, auth, health), and exposes commands as `Tool` instances dynamically.

### Rejected Alternatives

- **Flat Tool Registration:** Each CLI command as an independent `Tool`. No app-level lifecycle, auth, or grouping. Doesn't scale.
- **MCP App Bridge:** Each CLI as its own MCP server process. Too heavy for simple CLIs, operational complexity.

## Domain Model

### App Entity

```
App
├── id: AppId (String)
├── name: String                     // "github", "gcloud", "gws"
├── displayName: String              // "GitHub CLI", "Google Workspace CLI"
├── binary: String                   // "gh", "gcloud", "gws"
├── version: Option[String]          // detected at health check
├── commands: List[AppCommand]       // discovered or declared
├── auth: AppAuth                    // how the app authenticates
├── sandbox: AppSandbox              // native | sandboxed | docker
├── status: AppStatus                // installed | auth_required | healthy | unhealthy
├── tags: Set[String]                // "git", "cloud", "productivity"
└── enabled: Boolean
```

### AppCommand

```
AppCommand
├── name: String                     // "pr-list", "issue-create"
├── cliTemplate: String              // "gh pr list --json number,title {{flags}}"
├── description: String
├── parameters: Json                 // JSON Schema for command inputs
├── outputFormat: OutputFormat       // json | text | csv
└── tags: Set[String]
```

### AppAuth

```
AppAuth
├── method: AuthMethod               // token | oauth | service_account | none
├── checkCommand: Option[String]     // "gh auth status"
└── envVars: List[String]            // ["GITHUB_TOKEN", "GH_TOKEN"]
```

### AppSandbox

```
enum AppSandbox:
  case Native                        // direct exec, full access
  case Sandboxed                     // restricted fs/env
  case Docker                        // full container isolation
```

## Architecture

### AppRegistry (Event-Sourced)

Follows the `WorkspaceRepository` pattern with `EventStore[AppId, AppEvent]`.

#### Events

```
AppEvent
├── Registered(appId, name, binary, commands, auth, sandbox, occurredAt)
├── Updated(appId, name, binary, commands, auth, sandbox, occurredAt)
├── CommandsDiscovered(appId, commands, occurredAt)
├── HealthChecked(appId, status, version, occurredAt)
├── Enabled(appId, occurredAt)
├── Disabled(appId, occurredAt)
├── Deleted(appId, occurredAt)
```

### AppToolBridge

Converts `AppCommand` → `Tool` dynamically:

```
AppToolBridge
├── toTool(app, command) → Tool
├── toTools(app) → List[Tool]
└── registerAll(registry, apps) → UIO
```

The `execute` function inside each bridged `Tool`:
1. Renders `cliTemplate` with JSON parameters
2. Applies sandbox rules (native → direct, sandboxed → restricted env, docker → DockerSupport)
3. Runs via `CommandRunner`
4. Parses output based on `outputFormat`

### CommandRunner Trait

```scala
trait CommandRunner:
  def run(argv: List[String], cwd: String, env: Map[String, String]): IO[AppError, CommandOutput]

case class CommandOutput(exitCode: Int, stdout: String, stderr: String)
```

Production delegates to `CliAgentRunner.runProcess`. Tests use canned responses.

### Workflow Integration

App commands become available as `TaskStep` values. A step like `"gh:pr-list"` gets resolved by `TaskExecutor` via `AppToolBridge` lookup in `ToolRegistry`.

```
Workflow step "gh:pr-list"
  → TaskExecutor looks up tool "gh:pr-list" in ToolRegistry
  → Executes via AppToolBridge → gh pr list --json ...
  → Result feeds into next workflow node
```

### Discovery Flow (Startup)

1. Load app configs from HOCON
2. For each app:
   a. Check binary exists (`which <binary>`)
   b. If app supports self-describe, parse available commands
   c. Merge discovered commands with config-declared commands (config wins)
   d. Run auth check command if defined
   e. Emit `HealthChecked` event
   f. Register healthy app commands into `ToolRegistry`
3. Schedule periodic health checks

### MCP Exposure

New tools added to `GatewayMcpTools`:
- `list_apps` — list registered apps and their status
- `run_app_command` — invoke any app command by name
- `app_health` — check health of a specific app

## Configuration

```hocon
gateway {
  apps {
    github {
      display-name = "GitHub CLI"
      binary = "gh"
      auth {
        method = "token"
        check-command = "gh auth status"
        env-vars = ["GITHUB_TOKEN", "GH_TOKEN"]
      }
      sandbox = "native"
      tags = ["git", "devops"]
      commands {
        pr-list {
          cli-template = "gh pr list --repo {{repo}} --state {{state}} --json number,title,author,url"
          description = "List pull requests"
          output-format = "json"
          parameters {
            repo { type = "string", required = true, description = "owner/repo" }
            state { type = "string", required = false, default = "open" }
          }
        }
        issue-create {
          cli-template = "gh issue create --repo {{repo}} --title {{title}} --body {{body}}"
          description = "Create a GitHub issue"
          output-format = "text"
          parameters {
            repo { type = "string", required = true }
            title { type = "string", required = true }
            body { type = "string", required = true }
          }
        }
      }
      discover = true
    }
  }
}
```

## Security Model

### Per-Sandbox Restrictions

| Sandbox | Filesystem | Network | Env vars | Process |
|---------|-----------|---------|----------|---------|
| `native` | Full access | Full | Inherited | Direct exec |
| `sandboxed` | Workspace root only | Allowed | Filtered (only declared envVars) | Restricted PATH |
| `docker` | Mounted volumes only | Configurable | Explicit pass-through | Container isolation |

### Agent-Level Permissions

```hocon
agents {
  dev-agent {
    allowed-apps = ["github"]
    allowed-app-tags = ["devops"]
  }
}
```

`AppToolBridge` checks permissions before registering tools for a given agent's context.

### Audit

All app command executions emit `ActivityEvent` with app name, command, parameters (redacted secrets), and result status.

## Error Handling

```
AppError
├── BinaryNotFound(app, binary)
├── AuthFailed(app, message)
├── CommandFailed(app, command, exitCode, stderr)
├── PermissionDenied(agent, app)
├── SandboxViolation(app, message)
├── OutputParseError(app, command, raw)
├── DiscoveryFailed(app, message)
└── HealthCheckFailed(app, message)
```

## Testing Strategy

- **Unit tests:** Template rendering, parameter validation, output parsing. Mock `CommandRunner`.
- **Integration tests:** Event sourcing lifecycle. In-memory `EventStore`.
- **Contract tests:** Bundled app configs produce valid CLI commands with sample parameters.
- **No live CLI tests in CI** — all behind `CommandRunner` trait.

## Component Summary

| Component | Package | Purpose |
|-----------|---------|---------|
| `App`, `AppCommand`, `AppAuth`, `AppSandbox` | `apps.entity` | Domain model |
| `AppEvent` | `apps.entity` | Event-sourced events |
| `AppRepository` | `apps.entity` | Read-side projection |
| `AppEventStoreES` | `apps.entity` | EclipseStore persistence |
| `AppRegistry` | `apps.control` | Lifecycle, discovery, health |
| `AppToolBridge` | `apps.control` | AppCommand → Tool conversion |
| `CommandRunner` | `apps.control` | Process execution abstraction |
| `AppController` | `apps.boundary` | REST API for CRUD + health |
| Gateway MCP tools | `mcp` | `list_apps`, `run_app_command`, `app_health` |
| Config | `config.entity` | `AppModels.scala` — HOCON mapping |
| Agent permissions | `config.entity` | `AgentModels` extension |
