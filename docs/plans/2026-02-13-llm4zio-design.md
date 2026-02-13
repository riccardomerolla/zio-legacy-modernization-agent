# llm4zio Module Design

**Date:** 2026-02-13
**Issue:** #137
**Status:** Approved

## Overview

Create a foundational ZIO-native LLM framework (`llm4zio`) as an internal library, providing clean abstractions for LLM operations with streaming, conversation history, tool calling, and observability. This replaces the existing `AIService` with a more capable and modular design.

## Goals

- **Isolation & Reusability**: Separate generic LLM framework from legacy modernization domain logic
- **Pure ZIO Architecture**: Full ZIO effects and ZLayer composition, no Cats dependencies
- **Enhanced Capabilities**: Streaming, conversation history, tool calling support
- **Production Ready**: Comprehensive error handling, observability (logging + metrics)
- **Future Extraction**: Design allows extraction as standalone library later

## Architecture Decisions

### Module Structure: Single Module with Package Organization

**Chosen Approach:** Single `llm4zio` sbt module with clean package structure

**Rationale:**
- Simpler build configuration during active development
- Easier refactoring across packages
- Can split into multi-module later if needed (package structure supports this)
- All isolation benefits without multi-module complexity

**Structure:**
```
llm4zio/
├── src/main/scala/llm4zio/
│   ├── core/          (LlmService trait, core models, RateLimiter)
│   ├── providers/     (Gemini, OpenAI, Anthropic implementations)
│   ├── agents/        (agent framework - placeholder for future)
│   ├── tools/         (tool calling infrastructure)
│   └── observability/ (metrics and logging)
└── src/test/scala/llm4zio/
    └── (mirrored test structure)
```

### LlmService API Design

**Replaces:** Existing `AIService` trait (complete replacement, not extension)

**Core Interface:**
```scala
package llm4zio.core

trait LlmService:
  // Basic execution
  def execute(prompt: String): IO[LlmError, LlmResponse]

  // Streaming primitives
  def executeStream(prompt: String): Stream[LlmError, LlmChunk]

  // Conversation support
  def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]
  def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]

  // Tool calling
  def executeWithTools(prompt: String, tools: List[Tool]): IO[LlmError, ToolCallResponse]

  // Structured output
  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]

  // Health check
  def isAvailable: UIO[Boolean]
```

**Key Types:**
```scala
enum LlmProvider:
  case GeminiCli, GeminiApi, OpenAI, Anthropic

case class LlmConfig(
  provider: LlmProvider,
  model: String,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  // ... rate limiting config
)

case class LlmResponse(
  content: String,
  usage: Option[TokenUsage] = None,
  metadata: Map[String, String] = Map.empty,
)

case class LlmChunk(
  delta: String,
  finishReason: Option[String] = None,
  usage: Option[TokenUsage] = None,
)

case class Message(
  role: MessageRole,
  content: String,
)

enum MessageRole:
  case System, User, Assistant, Tool
```

### Streaming Implementation

**Strategy:**
- For HTTP providers (OpenAI, Anthropic, Gemini API): Use SSE (Server-Sent Events) via zio-http
- For CLI provider (Gemini CLI): Stream stdout, fallback to collecting full output
- All providers implement consistent `Stream[LlmError, LlmChunk]` interface
- Helper extension method `collectResponse` to materialize full response from stream

**Provider Interface:**
```scala
trait LlmProvider:
  def execute(messages: List[Message], config: LlmConfig): IO[LlmError, LlmResponse]
  def executeStream(messages: List[Message], config: LlmConfig): Stream[LlmError, LlmChunk]
```

### Tool Calling Infrastructure

**Approach:** Semi-automated with path to full automation

**Design:**
```scala
// Tool as ZIO effect
case class Tool[R, E, A](
  name: String,
  description: String,
  parameters: JsonSchema,
  execute: Json => ZIO[R, E, A],
)

// Tool registry manages available tools
trait ToolRegistry:
  def register[R, E, A](tool: Tool[R, E, A]): UIO[Unit]
  def get(name: String): IO[ToolError, Tool[Any, Any, Any]]
  def execute(call: ToolCall): IO[ToolError, ToolResult]

// Response with tool calls
case class ToolCallResponse(
  content: Option[String],
  toolCalls: List[ToolCall],
  finishReason: String,
)
```

**Usage Pattern (semi-automated):**
User orchestrates the tool calling loop manually:
```scala
for {
  response <- LlmService.executeWithTools(prompt, tools)
  results <- ZIO.foreach(response.toolCalls)(toolRegistry.execute)
  finalResponse <- LlmService.executeWithHistory(messages ++ results)
} yield finalResponse
```

**Future Enhancement:**
Add `AgentExecutor` in `llm4zio.agents` to run tool loops automatically until completion.

### Observability: Logging + Metrics

**Scope:** Structured logging and metrics tracking (defer OpenTelemetry tracing)

**Logging:**
- Structured logs via ZIO logging with annotations
- Log request/response with context (prompt length, duration, output length)
- Wrap LlmService with logging aspect

**Metrics:**
```scala
case class LlmMetrics(
  requestCount: Ref[Long],
  totalTokens: Ref[Long],
  totalLatencyMs: Ref[Long],
  errorCount: Ref[Long],
)

case class MetricsSnapshot(
  requests: Long,
  totalTokens: Long,
  totalLatencyMs: Long,
  errors: Long,
)
```

Track: request count, token usage, latency, error rate. Expose via ZIO service for dashboards.

### Error Handling

**Granular error types:**
```scala
sealed trait LlmError extends Throwable

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None) extends LlmError
  case class RateLimitError(retryAfter: Option[Duration] = None) extends LlmError
  case class AuthenticationError(message: String) extends LlmError
  case class InvalidRequestError(message: String) extends LlmError
  case class TimeoutError(duration: Duration) extends LlmError
  case class ParseError(message: String, raw: String) extends LlmError
  case class ToolError(toolName: String, message: String) extends LlmError
  case class ConfigError(message: String) extends LlmError
```

**Resilience:**
- Reuse existing `RetryPolicy` (move to llm4zio.core)
- Integrate with `RateLimiter` for backpressure
- Provider-specific retry logic (exponential backoff for rate limits)

### Type Safety (Scala 3)

- Enums for `LlmProvider`, `MessageRole`
- Opaque types for validated values (e.g., `ModelName`)
- Union types for flexible outputs: `type ToolOutput = String | Json | Unit`
- `derives JsonCodec` for all serializable types

## Migration Plan

**Strategy:** Big bang migration - all at once, update all imports

**Steps:**
1. Create llm4zio module structure (sbt config, directories, packages)
2. Move & rename core types (LlmService, Models, errors)
3. Move provider implementations (*AIService.scala → llm4zio.providers)
4. Update root project imports (core.* → llm4zio.core.*, models.AI* → llm4zio.core.*)
5. Delete old code (migrated files from core/models)
6. Verify compilation (`sbt compile`)
7. Run tests (`sbt test`) and fix failures

**Files to migrate:**
- `core/AIService.scala` → `llm4zio/core/LlmService.scala`
- `core/*AIService.scala` → `llm4zio/providers/*Provider.scala`
- `core/HttpAIClient.scala` → `llm4zio/providers/HttpClient.scala`
- `core/RateLimiter.scala` → `llm4zio/core/RateLimiter.scala`
- `core/ResponseParser.scala` → `llm4zio/providers/ResponseParser.scala`
- `core/RetryPolicy.scala` → `llm4zio/core/RetryPolicy.scala`
- `models/AIModels.scala` → `llm4zio/core/ProviderModels.scala`
- `models/ProviderModels.scala` → merge into `llm4zio/core/Models.scala`

**Affected packages in root project:**
- `agents/*` - update imports
- `orchestration/*` - update imports
- `prompts/*` - update imports
- `web/*` - update imports if any direct AIService usage

## Testing Strategy

**Test structure:**
```
llm4zio/src/test/scala/llm4zio/
├── core/
│   ├── LlmServiceSpec.scala
│   └── RateLimiterSpec.scala
├── providers/
│   ├── GeminiCliProviderSpec.scala
│   ├── GeminiApiProviderSpec.scala
│   ├── OpenAIProviderSpec.scala
│   └── AnthropicProviderSpec.scala
├── tools/
│   └── ToolRegistrySpec.scala
└── observability/
    └── LlmMetricsSpec.scala
```

**Test coverage:**
- Unit tests for each provider (mocked HTTP/CLI responses)
- Integration tests for streaming (chunk ordering, completion)
- Tool calling tests (registry, execution, error handling)
- Metrics tests (counts, latency tracking)
- Existing agent tests must pass after migration

## Verification Checklist

- [ ] `sbt compile` succeeds
- [ ] `sbt test` passes
- [ ] `sbt fmt` applied
- [ ] `sbt check` passes
- [ ] All existing agent functionality works with new LlmService

## Future Enhancements

- **Full agent automation** (`AgentExecutor` for autonomous tool loops)
- **RAG support** (embeddings, vector search in `llm4zio.rag`)
- **Advanced streaming** (tool calls in streams, structured output streaming)
- **OpenTelemetry integration** (leverage existing zio-opentelemetry)
- **Multi-module split** (if extracted as standalone library)
- **Additional providers** (Cohere, Claude via Bedrock, local models)

## Why Internal llm4zio vs External Dependency

- ✅ Full ZIO integration - no Cats interop overhead
- ✅ Complete control over API design and evolution
- ✅ Domain-specific optimizations for legacy modernization
- ✅ No external dependency risks or version conflicts
- ✅ Can extract to separate library later if desired

## Dependencies

**New dependencies:** None (reuse existing ZIO stack)

**Required from root:**
- `zio`, `zio-streams` (core effects)
- `zio-json` (serialization)
- `zio-http` (HTTP providers, SSE streaming)
- `zio-logging` (structured logging)
- `zio-test` (testing)

## Design Principles

1. **Pure ZIO effects** - no Cats, no Future, no blocking
2. **Full ZLayer composition** - dependency injection via ZLayer
3. **Modular design** - packages can evolve independently
4. **Type-safe** - leverage Scala 3 enums, opaque types, union types
5. **Production-ready** - error handling, retry, rate limiting, observability
6. **YAGNI** - build what's needed now, design for extension later
