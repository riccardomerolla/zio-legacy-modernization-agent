# Agent Orchestration Platform Design

**Date:** 2026-03-02
**Status:** Approved
**Goal:** Evolve llm4zio-gateway into a full agent orchestration platform with interactive sessions, git visibility, streamlined workflows, and agent management — inspired by GitHub Copilot Coding Agent and OpenCode.

## Design Principles

- **Hybrid interaction model**: Agents run autonomously by default, but users can attach to observe, interrupt, steer, or continue at any time
- **Read-only git visibility**: Show branch changes, diffs, commit logs — users manage git via their own tools
- **HTMX/Scalatags UI**: All new features built within the existing server-rendered stack
- **Incremental delivery**: Four phases, each delivers visible value and builds foundation for the next
- **Event-sourced domain**: All new entities follow the existing event-sourcing pattern with EclipseStore

---

## Phase 1: Interactive Run Sessions

**Goal:** Transform runs from fire-and-forget to interactive collaborative sessions.

### 1.1 Bidirectional Process Communication

- Extend `CliAgentRunner` to capture stdin/stdout/stderr
- New `InteractiveAgentRunner` that keeps the process alive and accepts user input via stdin pipe
- For CLI tools that don't support interactive mode (e.g., `claude --print`), fall back to "continuation runs" — a new run that picks up context from the previous conversation

### 1.2 Run Session Lifecycle

- New run sub-states: `Running.Autonomous` | `Running.Interactive` | `Running.Paused`
- User can "attach" to a running session (WebSocket subscription + input channel)
- User can "detach" without stopping the agent
- User can "interrupt" (pause agent, await user input) or "cancel" (kill process)

### 1.3 User Message Input on Run Conversations

- Extend `ChatController` / WebSocket protocol to accept user messages on run conversations
- Messages route to the agent's stdin (if interactive) or queue for continuation
- New WebSocket client messages: `SendRunMessage(runId, content)`, `InterruptRun(runId)`, `ContinueRun(runId, prompt)`

### 1.4 Continuation Runs

- When agent completes or is interrupted, user can "continue from here"
- Creates a new run on the same worktree/branch, with conversation history as context
- Prompt includes summary of previous work + new user instructions

### 1.5 UI Changes

- Run detail view gets an input box (disabled when agent is autonomous, enabled when interactive/paused)
- "Attach" / "Detach" / "Interrupt" / "Continue" buttons on run detail
- Visual indicator of run mode (autonomous vs interactive)

---

## Phase 2: Git Workspace Visibility

**Goal:** Give users visibility into what the agent changed in the workspace/worktree.

### 2.1 Git Status Service

- New `GitService` in `workspace/control/` wrapping git CLI commands via ZIO Process
- Operations: `status(path)`, `diff(path)`, `diffStat(path)`, `log(path, limit)`, `branchInfo(path)`, `showFile(path, file)`
- Typed models: `GitStatus(branch, staged, unstaged, untracked)`, `GitDiff(files: List[DiffFile])`, `GitLogEntry(hash, author, message, date)`

### 2.2 Workspace Run Git View API

- `GET /api/workspaces/:id/runs/:runId/git/status` — branch, changed files, staged/unstaged
- `GET /api/workspaces/:id/runs/:runId/git/diff` — diff content
- `GET /api/workspaces/:id/runs/:runId/git/log` — commit log on the run's branch
- `GET /api/workspaces/:id/runs/:runId/git/diff/:file` — single file diff

### 2.3 Real-time Git Updates

- New WebSocket topic: `runs:{runId}:git` — publishes git status changes
- Periodic polling (every 5s while run is active) via `GitWatcher` fiber
- Publishes delta events when files change

### 2.4 UI: Git Panel on Run Detail

- Collapsible "Changes" panel on the run detail page
- Shows: branch name, commit count ahead of main, file list with status indicators (M/A/D/?)
- Click file to see diff (inline, HTMX-loaded)
- Commit log section with hash, message, author

---

## Phase 3: Issue-Workspace-Run Workflow

**Goal:** Streamline the full workflow from issue creation to agent execution.

### 3.1 Issue-Workspace Binding

- Issues can be linked to a workspace at creation time (optional `workspaceId` field)
- When linked, "Assign" on an issue automatically creates a run in that workspace
- If not linked, user picks workspace at assignment time

### 3.2 Issue Board View

- Kanban-style board: columns = Open | Assigned | In Progress | Completed | Failed
- Cards show: title, priority badge, assigned agent, workspace name, run status
- Click card → issue detail with linked run conversation
- Drag-and-drop between columns (HTMX + minimal JS)

### 3.3 Run Status Dashboard

- Unified view of all active runs across all workspaces
- Shows: workspace, issue, agent, status, duration, last activity
- Quick actions: attach, cancel, view conversation, view git changes
- Auto-refreshes via WebSocket subscription

### 3.4 Issue Templates

- Predefined issue templates (bug fix, feature, refactor, review)
- Each template can have default workspace, agent, priority, tags
- Templates stored in config

### 3.5 Bulk Operations

- Select multiple issues → assign all to same workspace/agent
- Batch status updates
- Import issues from external sources

---

## Phase 4: Agent Management & Orchestration

**Goal:** Make agents first-class entities with profiles, capabilities, and smarter assignment.

### 4.1 Agent Registry

- First-class `Agent` entity (event-sourced): id, name, description, cliTool, capabilities, default model, system prompt, max concurrent runs
- `AgentRepository` with CRUD operations
- Agents management page in UI

### 4.2 Agent Capabilities & Matching

- Agents declare capabilities: `["scala", "testing", "refactoring", "frontend", ...]`
- Issues can specify required capabilities
- Suggest best-matching agents based on capability overlap + availability
- Optional auto-assignment

### 4.3 Agent Execution Metrics

- Track per-agent: total runs, success rate, avg duration, active runs
- Dashboard widget showing agent utilization
- Historical performance trends

### 4.4 Multi-Agent Coordination

- Multiple agents on the same issue (e.g., one writes code, another reviews)
- Sequential pipeline: Agent A → Agent B on same worktree
- Parallel: multiple agents on different worktrees for the same issue
- Orchestration via the existing `OrchestratorControlPlane`

### 4.5 Agent Configuration Profiles

- Environment variables per agent
- Custom CLAUDE.md / system prompts injected into worktree
- Resource limits (timeout, memory, concurrent runs)

---

## Technical Considerations

### Persistence
- All new entities (Agent) follow event-sourcing with EclipseStore
- Extend existing WorkspaceRun events for new lifecycle states
- Extend AgentIssue events for workspace binding

### WebSocket Protocol Extensions
- New client messages: `SendRunMessage`, `InterruptRun`, `ContinueRun`, `AttachToRun`, `DetachFromRun`
- New server events: `RunStateChanged`, `GitStatusUpdate`, `AgentMetricsUpdate`
- New topics: `runs:{runId}:git`, `agents:metrics`

### Process Management
- `InteractiveAgentRunner` needs careful stdin/stdout buffering
- Process lifecycle must be tied to ZIO Scope for cleanup
- Docker mode needs stdin passthrough (`docker run -i`)

### UI Architecture
- All new views as Scalatags components with HTMX attributes
- WebSocket subscriptions for live updates
- Minimal JavaScript only where HTMX is insufficient (kanban drag-and-drop, diff viewer)
