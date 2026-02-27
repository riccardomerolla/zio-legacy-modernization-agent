# Design: Fix Type Boundary Violations + Rename API DTOs

**Date:** 2026-02-27
**Scope:** Issues and Conversation modules
**Approach:** Option B — Rename DTOs + remove duplicate issue path from ChatRepository

---

## Problem

Two `AgentIssue` types exist:
- `issues.entity.AgentIssue` — domain aggregate (event-sourced, strong types)
- `issues.entity.api.AgentIssue` — API DTO (weak `Option[String]` types, for HTTP)

The API DTO type is incorrectly used in the **repository**, **service**, and **orchestration** layers. Only the **boundary (controller)** and **view** layers should use API DTOs.

Same violation exists for `conversation.entity.ChatApiModels` types (`ChatConversation`, `ConversationEntry`).

---

## Design Decisions

### 1. Rename API DTOs with `*View` suffix

| Old | New | Location |
|-----|-----|----------|
| `issues.entity.api.AgentIssue` | `AgentIssueView` | `IssueApiModels.scala` |
| `issues.entity.api.AgentAssignment` | `AgentAssignmentView` | `IssueApiModels.scala` |
| `conversation.entity.ChatConversation` | `ChatConversationView` | `ChatApiModels.scala` → moved to `conversation/entity/api/` |
| `conversation.entity.ConversationEntry` | `ConversationEntryView` | same |

`IssuePriority`, `IssueStatus`, `MessageType`, `SenderType` enums keep their names (no ambiguity).

Request types (`AgentIssueCreateRequest`, `AssignIssueRequest`, `ConversationMessageRequest`, `ChatConversationCreateRequest`) also keep their names.

### 2. Remove Issue/Assignment Methods from `ChatRepository`

Strip from `ChatRepository` and `ChatRepositoryLive`:
- `createIssue`, `getIssue`, `listIssues`, `listIssuesByRun`, `listIssuesByStatus`, `updateIssue`, `deleteIssue`
- `createAssignment`, `getAssignment`, `listAssignmentsByIssue`, `updateAssignment`

All callers switch to `IssueRepository` (event-sourced, domain-typed).

### 3. Fix Call Sites

**`IssueController`** (boundary — correct layer for View types):
- Convert `AgentIssueCreateRequest` → `IssueEvent.Created` → `IssueRepository.append()`
- Fetch domain `AgentIssue` from `IssueRepository.get()`
- Convert domain → `AgentIssueView` for HTTP response

**`IssueAssignmentOrchestrator`** (control — should use domain types):
- Fetch `AgentIssue` from `IssueRepository` (domain type)
- Convert to `AgentIssueView` only at return boundary

**`WorkspaceRunService`** (control — should use domain types):
- Fetch `AgentIssue` from `IssueRepository` (domain type)
- Access `.title`, `.description`, `.contextPath` directly from domain type

### 4. Move ChatApiModels

Move `conversation/entity/ChatApiModels.scala` → `conversation/entity/api/ChatApiModels.scala`
Update package declaration and all imports.

---

## Files to Modify

| File | Change |
|------|--------|
| `issues/entity/api/IssueApiModels.scala` | Rename `AgentIssue` → `AgentIssueView`, `AgentAssignment` → `AgentAssignmentView` |
| `conversation/entity/ChatApiModels.scala` | Rename types + move to `conversation/entity/api/` |
| `db/ChatRepository.scala` | Remove issue/assignment methods |
| `db/ChatRepositoryLive.scala` | Remove issue/assignment implementations + row conversions |
| `issues/boundary/IssueController.scala` | Use `IssueRepository` domain path, convert → `AgentIssueView` for responses |
| `orchestration/control/IssueAssignmentOrchestrator.scala` | Use `IssueRepository`, domain types internally |
| `workspace/control/WorkspaceRunService.scala` | Use `IssueRepository`, domain `AgentIssue` |
| `shared/web/IssuesView.scala` | Update import to `AgentIssueView` |
| `shared/web/HtmlViews.scala` | Update import to `AgentIssueView` |
| `shared/web/ChatView.scala` | Update to `ChatConversationView`, `ConversationEntryView` |
| `shared/web/WorkspacesView.scala` | Update imports |
| `conversation/boundary/ChatController.scala` | Update imports to `conversation.entity.api.*` |
| `models/ChatModels.scala` | Update type aliases |
| `workspace/boundary/WorkspacesController.scala` | Remove unused `IssueStatus` import |
| Test files | Update imports and type usage |

---

## Correct Layer Boundaries (Post-Fix)

```
HTTP Request
    ↓
Controller (imports *View types, converts request → IssueEvent)
    ↓
IssueRepository (domain AgentIssue, event-sourced)
    ↓
EclipseStore (events as JSON strings)
    ↓
IssueRepository (returns domain AgentIssue)
    ↓
Controller (converts domain → *View for HTTP response)
    ↓
HTTP Response (AgentIssueView JSON)
```

Views (`IssuesView`, `ChatView`) correctly use `*View` types — no change needed in logic, only import updates.

---

## Out of Scope

- Splitting `ChatRepository` conversation/session methods (kept as-is for now)
- Changing `IssueRepositoryES` internals
- Changing EclipseStore registration in `DataStoreModule`
