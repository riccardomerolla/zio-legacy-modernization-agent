# Navigation & Settings UX Redesign

**Date:** 2026-02-24
**Status:** Approved
**Related:** #268 (Multi-channel management)

## Problem

The sidebar has 14+ nav items with no clear hierarchy. Config-related pages (`/config`, `/models`, `/settings`, `/channels`, `/health`) are separate nav entries but all belong to the same mental model: "system configuration and monitoring". Analysis pages (Reports, Graph) are rarely visited standalone — they exist in context of a task run. The current Settings page mixes AI config, gateway config, Telegram config, and memory in one long form with no structure.

## Design Decisions

1. **Consolidate**: `/config`, `/models`, `/channels`, `/health`, `/settings` → single `/settings` with tabs
2. **Demote**: Reports and Graph removed from nav, accessible from task detail pages only
3. **Simplify**: New Task removed from nav, becomes `+ New Task` button on Tasks page header
4. **Channel config inline**: Telegram (and future Discord/Slack) config moves from Settings form into per-channel expandable card
5. **Deep-linkable tabs**: `/settings/:tab` URLs for bookmarkability

## New Navigation Structure

```
MAIN
  Dashboard      /
  Tasks          /tasks
  Workflows      /workflows

WORKSPACE
  Chat           /chat
  Issues         /issues
  Activity       /activity
  Memory         /memory

AGENTS
  Agents         /agents
  Agent Monitor  /agent-monitor

SYSTEM (pinned bottom)
  Settings       /settings/ai   (default tab)
```

**Removed from nav:**
- Reports (`/reports`) — linked from task detail
- Graph (`/graph`) — linked from task detail
- New Task (`/tasks/new`) — button on Tasks page
- Config (`/config`) — becomes Settings > Advanced Config tab
- Models (`/models`) — becomes Settings > AI Models tab
- Channels (`/channels`) — becomes Settings > Channels tab
- Health (`/health`) — becomes Settings > System tab

## Settings Page — Tabbed Layout

Route: `/settings/:tab` where tab ∈ {ai, channels, gateway, system, advanced}

### Tab 1: AI Models (`/settings/ai`)

Consolidates: current Settings AI Provider section + standalone Models page

**Form section — Provider Configuration:**
- Provider dropdown: GeminiCli, GeminiApi, OpenAI, Anthropic, LmStudio, Ollama
- Model (text)
- Base URL (optional, for local models)
- API Key (password)
- Timeout, Max Retries, Requests/min, Burst Size, Acquire Timeout
- Temperature (optional), Max Tokens (optional)
- Fallback Chain (comma-separated provider:model)
- [Test Connection] button → inline HTMX result

**Models table section:**
- Grouped by provider with health badge
- Columns: Model ID, Context Window, Capabilities
- Refreshes via HTMX

### Tab 2: Channels (`/settings/channels`)

Consolidates: standalone Channels page + Telegram section from Settings form

**Channel cards grid** (auto-refresh every 10s):
- Each card: name, status pill, message stats, last activity
- [Configure ▼] toggle → inline expansion with channel-specific form:

  **Telegram card config:**
  - Bot Token (password)
  - Mode: Polling / Webhook
  - Conditional: Webhook URL + Secret, or Polling Interval + Batch Size + Timeout
  - [Save] → POST to settings endpoint, inline success/error

  **Discord card config (future):**
  - Bot Token, Guild ID, Channel routing

  **Slack card config (future):**
  - App Token, Socket Mode

  **WebSocket card:**
  - Status only (built-in, no user config)

- `+ Add Channel` button → small form (type dropdown + token input)

### Tab 3: Gateway (`/settings/gateway`)

Consolidates: current Settings Gateway section + Memory section

**Gateway section:**
- Gateway Name
- Dry Run Mode (checkbox)
- Verbose Logging (checkbox)

**Memory section:**
- Enable Memory (checkbox)
- Max Context Memories
- Summarization Threshold
- Retention Days

**Danger Zone (bottom):**
- Reset Operational Data (red box with confirmation)

### Tab 4: System (`/settings/system`)

Consolidates: standalone Health page

- Embeds health dashboard widgets (currently at `/health`)
- Store stats, agent health summary, channel health
- Links to logs for each component

### Tab 5: Advanced Config (`/settings/advanced`)

Consolidates: standalone Config page (`/config`)

- Raw HOCON/JSON editor web component (`<config-editor>`)
- Label: "For advanced users. Most settings are configurable via the tabs above."
- History, diff, rollback preserved

## Route Changes

| Old Route | New Behaviour |
|-----------|---------------|
| `/config` | HTTP 302 redirect → `/settings/advanced` |
| `/models` | HTTP 302 redirect → `/settings/ai` |
| `/channels` | HTTP 302 redirect → `/settings/channels` |
| `/health` | HTTP 302 redirect → `/settings/system` |
| `/settings` (GET) | HTTP 302 redirect → `/settings/ai` |
| `/reports` | Stays, accessible from task detail |
| `/graph` | Stays, accessible from task detail |
| `/tasks/new` | Stays as route, but no nav entry |

## Tab Navigation HTML Pattern

Using Tailwind underline tabs (dark theme):

```html
<div class="border-b border-white/10">
  <nav class="-mb-px flex space-x-8">
    <a href="/settings/ai"
       class="border-b-2 border-indigo-500 py-4 px-1 text-sm font-medium text-white"
       aria-current="page">AI Models</a>
    <a href="/settings/channels"
       class="border-b-2 border-transparent py-4 px-1 text-sm font-medium text-gray-400 hover:text-white hover:border-white/30">
      Channels</a>
    <!-- ... -->
  </nav>
</div>
```

## Channel Card Inline Config Pattern

```
┌────────────────────────────────────────────────────────┐
│  Telegram                         ● Connected           │
│  Polling · @mybot                                       │
│  ↑142  ↓89  Errors: 0  Last: 2 min ago   [Configure ▼] │
├────────────────────────────────────────────────────────┤  ← toggle
│  Bot Token  [●●●●●●●●●●●●●]                            │
│  Mode       [Polling ▼]                                │
│  Interval   [1  ] s   Batch [100]   Timeout [30] s     │
│                                             [Save]     │
└────────────────────────────────────────────────────────┘
```

Config toggle: HTMX `hx-get="/settings/channels/:name/config-form"` + `hx-swap="afterend"`

## Implementation Scope

### Files to Modify
- `shared/web/Layout.scala` — new nav structure (10 items, 3 sections)
- `shared/web/SettingsView.scala` — rewrite as tabbed page
- `shared/web/ChannelView.scala` — inline config expansion, move into settings tab
- `shared/web/ModelsView.scala` — embed in AI Models tab
- `shared/web/HealthDashboard.scala` — embed in System tab

### Files to Create
- `shared/web/SettingsTabsView.scala` — new tabbed settings page (or integrate into SettingsView)

### Controllers to Modify
- `config/boundary/SettingsController.scala` — add `/settings/:tab` routing, channel config save endpoints
- `gateway/boundary/ChannelController.scala` — add per-channel config form endpoints

### Redirects to Add
- `/config` → `/settings/advanced`
- `/models` → `/settings/ai`
- `/channels` → `/settings/channels`
- `/health` → `/settings/system`
- `/settings` → `/settings/ai`

### Server Routing
- `app/boundary/WebServer.scala` (or equivalent routing file) — add `/settings/:tab` routes
