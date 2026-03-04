# llm4zio

[![Maven Central](https://img.shields.io/maven-central/v/io.github.riccardomerolla/llm4zio.svg)](https://mvnrepository.com/artifact/io.github.riccardomerolla/llm4zio)
[![Test Coverage](https://img.shields.io/badge/tests-450+-green)]()

A purely functional, ZIO-native LLM integration library and production-grade AI gateway for Scala 3.

Built from the ground up for **Effect-Oriented Programming**, `llm4zio` provides type-safe, resource-safe, and composable abstractions for interacting with Large Language Models and orchestrating multi-agent workflows.

---

## ⚡ Why llm4zio?

- **ZIO Native**: Everything is a `ZIO[R, E, A]`. No hidden side effects, no thrown exceptions.
- **Typed Errors**: Exhaustive pattern matching on API failures, rate limits, and context length issues via `LLMError`.
- **Streaming First**: Backpressured token streaming using `ZStream`.
- **Resource Safe**: Connection pooling and HTTP client lifecycles managed automatically via `Scope` and `ZLayer`.
- **Resilient**: Built-in support for exponential backoff, circuit breakers, and token-bucket rate limiting.
- **Event Sourced**: All domain aggregates (agents, conversations, workspaces, issues) are fully event-sourced.

---

## 📦 Installation

```scala
libraryDependencies += "io.github.riccardomerolla" %% "llm4zio" % "1.0.3"
```

---

## 🚀 Quick Start

```scala
import zio._
import zio.stream._
import io.github.riccardomerolla.llm4zio._

val program: ZIO[LLMService, LLMError, Unit] = for {
  // Simple completion
  response <- ZIO.serviceWithZIO[LLMService](_.complete(
    ChatRequest(
      messages = List(ChatMessage.user("What is ZIO?")),
      model = "gpt-4o"
    )
  ))
  _ <- Console.printLine(s"Response: ${response.content}")

  // Streaming with backpressure
  _ <- ZStream.serviceWithStream[LLMService](_.stream(
    ChatRequest(
      messages = List(ChatMessage.user("Write a Scala 3 macro.")),
      model = "gpt-4o"
    )
  )).foreach(chunk => Console.print(chunk))
} yield ()

program.provide(
  LLMService.live(LLMConfig(
    provider = "openai",
    apiKey = sys.env.get("OPENAI_API_KEY"),
    model = "gpt-4o"
  ))
)
```

---

## 🛡️ Typed Error Handling

Never catch `Throwable` again. Handle specific LLM failures exhaustively:

```scala
def robustCall(req: ChatRequest): ZIO[LLMService, Nothing, String] =
  ZIO.serviceWithZIO[LLMService](_.complete(req)).map(_.content).catchAll {
    case LLMError.RateLimited(retryAfter) =>
      ZIO.logWarning(s"Rate limited. Retrying in $retryAfter") *>
      ZIO.sleep(retryAfter) *> robustCall(req)
    case LLMError.ContextLengthExceeded =>
      ZIO.succeed("Prompt too long. Please truncate.")
    case LLMError.ApiError(msg, code) =>
      ZIO.logError(s"API Error $code: $msg") *> ZIO.succeed("Fallback response")
    case e: LLMError =>
      ZIO.logError(s"Unexpected LLM error: $e") *> ZIO.succeed("Error")
  }
```

---

## 🧪 Testing

`llm4zio` provides `TestLLMService` for deterministic, fast unit testing without hitting real APIs:

```scala
import zio.test._
import io.github.riccardomerolla.llm4zio.test._

object MyAgentSpec extends ZIOSpecDefault {
  def spec = suite("MyAgent")(
    test("handles responses") {
      for {
        _ <- TestLLMService.setResponse("Mocked LLM response")
        res <- MyAgent.run("Hello")
      } yield assertTrue(res == "Mocked LLM response")
    }
  ).provide(TestLLMService.mock)
}
```

---

## 🏗️ Architecture

The project is structured as a layered system:

```
┌─────────────────────────────────────────────────────────┐
│                  llm4zio-gateway                        │
│   Multi-channel AI gateway (Telegram, Slack, Discord,   │
│   WebSocket) with workflow orchestration and MCP server │
├──────────────────────┬──────────────────────────────────┤
│  Orchestration Layer │  Workspace Layer                 │
│  (Workflows, Agents, │  (Git repos, CLI/Docker runners, │
│   Parallel Sessions) │   interactive agent sessions)    │
├──────────────────────┴──────────────────────────────────┤
│                  llm4zio core                           │
│   LLM client abstractions, streaming, typed errors      │
└─────────────────────────────────────────────────────────┘
```

---

## 📖 Core Library Features

### LLM Client Abstraction
- Unified `LLMService` interface across providers (OpenAI, Anthropic, Gemini, local)
- Streaming completions via `ZStream` with full backpressure
- Typed `LLMError` ADT: `RateLimited`, `ContextLengthExceeded`, `ApiError`, and more
- Configurable retry policies with exponential backoff

### Agent Domain Model
- Full event-sourced `Agent` aggregate with capabilities, concurrency limits, and Docker resource quotas
- `AgentRegistry`: runtime discovery, capability-based routing, performance ranking, health tracking
- `AgentDispatcher`: parallel agent execution with `ZIO.foreachPar`
- `AgentConfigResolver`: dynamic configuration loading and capability matching

### Conversation Management
- Event-sourced `Conversation` aggregate (channels: Telegram, Web, Internal)
- Message types: Text, Code, Error, Status
- Sender roles: User, Assistant, System
- Full conversation history and lifecycle management

### Task Execution (TaskRun)
- `TaskRun` event-sourced aggregate with phases, reports, and artifact collection
- States: Pending → Running → Completed / Failed / Cancelled
- Real-time progress streaming via `ProgressTracker`

### Issue Tracking
- `AgentIssue` aggregate: title, description, priority, required capabilities, tags
- Full lifecycle: Open → Assigned → InProgress → Completed / Failed / Skipped
- `IssueAssignmentOrchestrator`: intelligent issue-to-agent matching based on capabilities

### Memory and Context
- Episodic/semantic memory system with vector embedding support
- Memory types: Preference, Fact, Context, Summary
- Similarity-scored retrieval, per-user/per-session filtering
- `EmbeddingService` for vector store integration

### Configuration Management
- Typed setting values: Text, Flag, Whole, Decimal
- Workflow definition storage with step ordering
- Custom agent configuration with full CRUD and versioning

### Activity Audit Log
- `ActivityEvent` stream: RunStarted, RunCompleted, AgentAssigned, MessageSent, ConfigChanged
- `ActivityHub` for real-time broadcasting of audit events

### Event Sourcing Infrastructure
- Generic `EventStore[E]` trait with `append` / `getEvents` / `getAllEvents`
- In-memory and persistent backends
- Event projection pattern across all aggregates

---

## 🌐 Gateway Features (llm4zio-gateway)

### Multi-Channel Support
| Channel | Features |
|---------|----------|
| **Telegram** | Bot commands (`/start`, `/help`, `/workflow`, `/agent`), polling + webhook, keyboard UI, file transfer, progress notifications |
| **Slack** | Workspace integration, thread routing, message formatting |
| **Discord** | Guild and channel routing, message handling |
| **WebSocket** | Bi-directional real-time web clients, session lifecycle |

### Message Routing
- `MessageRouter`: session resolution with strategies — `PerUser`, `PerRun`, `Global`
- `GatewayService`: inbound/outbound queues, intent parsing, response chunking, memory enrichment
- `NormalizedMessage`: unified cross-channel message model (direction, role, metadata, session key)
- `IntentParser`: natural language intent classification, agent capability matching, clarification generation
- `ResponseChunker`: channel-aware message size splitting with reassembly support

### Session Management
- `ChatSession` with configurable `SessionScopeStrategy`
- `ChannelRegistry`: runtime channel registration/discovery with health status
- Session context tracking: conversation ID, run ID, metadata per channel

### Gateway Metrics
- Per-channel enqueue/process counts, chunking stats, error tracking
- `GatewayMetricsSnapshot` for real-time observability

---

## ⚙️ Orchestration Features

### Workflow Engine
- DAG-based workflow planning with topological sort for parallel batch execution
- Graph validation: circular dependency detection, missing dependency checks
- Dynamic graph operations: `insertNodeAfter`, `removeNode`, `updateAgentPolicy`
- Agent selection strategies: `CapabilityMatch`, `LoadBalanced`, `CostOptimized`, `PerformanceHistory`
- Conditional execution based on workflow context
- `WorkflowOrchestrator`: phase execution, progress callbacks, state checkpointing, error accumulation

### Parallel Workspace Sessions
Fan-out parallel agent execution inspired by AI coding workflows:

```
User request
    │
    ├── Agent 1 (Claude) → git worktree 1
    ├── Agent 2 (Gemini) → git worktree 2
    └── Agent 3 (...)    → git worktree 3
          │
          └─→ collect results → notify user via channel
```

- `ParallelSessionCoordinator`: launches N agents concurrently via `ZIO.foreachPar`, each in an isolated git worktree
- `ParallelWorkspaceSession`: session state with per-worktree run tracking (`WorktreeRunRef`)
- `ParallelSessionEvent` typed ADT: `SessionStarted`, `WorktreeAgentStarted`, `WorktreeAgentProgress`, `WorktreeAgentCompleted`, `WorktreeAgentFailed`, `SessionReadyForReview`
- `ParallelSessionFormatter`: converts events to `NormalizedMessage` for real-time channel delivery
- `DiffStats`: git diff summary per agent run (files changed, lines added/removed)
- Full `ParallelSessionStatus` lifecycle: Pending → Running → Collecting → ReadyForReview

### Control Plane
- `OrchestratorControlPlane`: centralized event pub/sub for workflow execution commands and progress reporting
- `Llm4zioAdapters`: protocol adaptation layer between internal models and external LLM services

---

## 🗂️ Workspace Features

### Workspace Model
- `Workspace`: local git repository with configurable agent, run mode (Host or Docker), and enable/disable
- `WorkspaceRun`: execution tracking with states Pending → Running → Completed / Failed / Cancelled
- Interactive modes: Autonomous, Interactive, Paused — with user attach/detach support
- Full event sourcing: Created, Updated, Enabled, Disabled, Deleted

### Execution Environments
- `CliAgentRunner`: direct host execution — CLI tool invocation, argument building, output capture
- `InteractiveAgentRunner`: interactive mode with process control (pause, resume, cancel) and I/O streaming
- `DockerSupport`: containerized execution with image management, volume mounting, network config, resource limits (memory/CPU from agent definition)

### Git Integration
- `GitService`: status, diff, diffStat, log, branchInfo, showFile, aheadBehind
- `GitWatcher`: filesystem monitoring with change event propagation
- `GitModels`: `GitStatus`, `GitDiff`, `GitDiffStat`, `GitLogEntry`, `GitBranchInfo`, `AheadBehind`

---

## 🔌 MCP Server (Model Context Protocol)

Exposes 7 tools for external LLM agents to interact with the gateway:

| Tool | Description |
|------|-------------|
| `assign_issue` | Create and assign a new issue with title, description, and priority |
| `run_agent` | Execute an agent on a workspace for a given issue |
| `get_agent_status` | Query current state, progress, and logs for a run |
| `get_memory` | Retrieve stored memory entries with semantic scoring |
| `save_memory` | Persist context, facts, or preferences for future recall |
| `list_agents` | Discover available agents with capabilities and status |
| `get_workspace` | Get workspace info, branch details, and current Git diff |

- SSE transport with session-based routing and session ID in response headers
- `McpService` manages server lifecycle as a scoped ZIO resource
- JSON schema generation for all tool parameters

---

## 🔐 Typed Domain Errors

All errors are typed ADTs — no stringly-typed failures:

```scala
// Agent and workspace errors
sealed trait ParallelSessionError
object ParallelSessionError:
  case class WorkflowNotFound(workflowId: String)                 extends ParallelSessionError
  case class WorkspaceNotFound(workspaceId: String)               extends ParallelSessionError
  case class SessionNotFound(sessionId: String)                   extends ParallelSessionError
  case class InsufficientResources(available: Int, required: Int) extends ParallelSessionError
  case class AgentAssignmentFailed(stepId: String, reason: String) extends ParallelSessionError
  case class WorktreeError(detail: String)                        extends ParallelSessionError
```

---

## 📚 Documentation

- [API Reference](docs/api-reference.md)
- [Provider Setup (OpenAI, Anthropic, Gemini, Local)](docs/providers.md)
- [Streaming Guide](docs/streaming.md)
- [Resilience & Rate Limiting](docs/resilience.md)
- [ADR-0001: Adopt openclaw Patterns](docs/adr/0001-adopt-openclaw-patterns.md)
- [Parallel Workspace Sessions Design](docs/plans/2026-03-04-parallel-workspace-sessions-design.md)

---

## 🗺️ Roadmap

- [ ] Persistent event store backend (PostgreSQL / EclipseStore)
- [ ] Parallel session review UI — branch diff viewer per worktree
- [ ] Workflow marketplace — shareable DAG templates
- [ ] Cost tracking and budget enforcement per session
- [ ] Agent performance history dashboard

---

## 🤝 Contributing

We welcome contributions! Please ensure your code follows Scala 3 idioms, uses `ZLayer` for DI, and maintains strict typed error channels (`ZIO[R, E, A]`). See [AGENTS.md](AGENTS.md) for our ZIO coding standards.
