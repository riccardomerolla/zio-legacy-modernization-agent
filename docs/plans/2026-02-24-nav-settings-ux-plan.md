# Navigation & Settings UX Redesign — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Collapse `/config`, `/models`, `/channels`, `/health` into a single tabbed `/settings/:tab` page; slim the sidebar from 14 items to 10; move channel config inline into channel cards.

**Architecture:** All changes are in the View and Controller layers only — no domain/persistence changes. The `SettingsController` grows new `/settings/:tab` routes. `ChannelController` gets a per-channel config-form endpoint. `Layout.scala` gets the new nav. Three pages (`/config`, `/models`, `/channels`, `/health`, `/settings`) all redirect to `/settings/:tab`.

**Tech Stack:** Scala 3, ZIO HTTP, Scalatags, Tailwind CSS v4, HTMX 2.

---

## Task 1: Slim the Sidebar

**Goal:** Remove 5 nav items; rename/regroup remaining 10.

**Files:**
- Modify: `src/main/scala/shared/web/Layout.scala`

**Step 1: Replace the `desktopSidebar` method**

Open `src/main/scala/shared/web/Layout.scala`. Replace the entire `desktopSidebar` private method (lines 34–103) with:

```scala
private def desktopSidebar(currentPath: String): Frag =
  div(
    cls := "hidden bg-gray-900 ring-1 ring-white/10 lg:fixed lg:inset-y-0 lg:z-50 lg:flex lg:w-72 lg:flex-col"
  )(
    div(cls := "flex grow flex-col gap-y-5 overflow-y-auto bg-black/10 px-6 pb-4")(
      div(cls := "flex h-16 shrink-0 items-center")(
        span(cls := "text-xl font-bold text-white")("A-B-Normal")
      ),
      nav(cls := "flex flex-1 flex-col")(
        ul(attr("role") := "list", cls := "flex flex-1 flex-col gap-y-7")(
          li(
            div(cls := "text-xs/6 font-semibold text-gray-400")("Main"),
            ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
              navItem("/", "Dashboard", Icons.home, currentPath == "/"),
              navItem("/tasks", "Tasks", Icons.folder, currentPath.startsWith("/tasks")),
              navItem("/workflows", "Workflows", Icons.workflow, currentPath.startsWith("/workflows")),
            ),
          ),
          li(
            div(cls := "text-xs/6 font-semibold text-gray-400")("Workspace"),
            ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
              navItem("/chat", "Chat", Icons.chat, currentPath.startsWith("/chat")),
              navItem("/issues", "Issues", Icons.flag, currentPath.startsWith("/issues")),
              navItem("/activity", "Activity", Icons.activity, currentPath.startsWith("/activity")),
              navItem("/memory", "Memory", Icons.archive, currentPath.startsWith("/memory")),
            ),
          ),
          li(
            div(cls := "text-xs/6 font-semibold text-gray-400")("Agents"),
            ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
              navItem("/agents", "Agents", Icons.cpuChip, currentPath.startsWith("/agents")),
              navItem("/agent-monitor", "Agent Monitor", Icons.monitor, currentPath.startsWith("/agent-monitor")),
            ),
          ),
          li(cls := "mt-auto")(
            ul(attr("role") := "list", cls := "-mx-2 space-y-1")(
              navItem(
                "/settings/ai",
                "Settings",
                Icons.cog,
                currentPath.startsWith("/settings") || currentPath.startsWith("/config") ||
                  currentPath.startsWith("/models") || currentPath.startsWith("/channels") ||
                  currentPath.startsWith("/health"),
              ),
            )
          ),
        )
      ),
    )
  )
```

**Step 2: Compile to verify no syntax errors**

```bash
sbt compile
```

Expected: Successful compilation. If there are errors, check that all icon names used (`Icons.home`, `Icons.folder`, etc.) already exist in the `Icons` object — they do based on current code.

**Step 3: Commit**

```bash
git add src/main/scala/shared/web/Layout.scala
git commit -m "feat: slim sidebar to 10 items with Main/Workspace/Agents/Settings sections"
```

---

## Task 2: Create the Tabbed Settings Shell

**Goal:** Build the new `SettingsView.settingsTabs(activeTab)` shared tab bar that all settings sub-pages use.

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala`

**Step 1: Add tab navigation helpers at the top of `SettingsView`**

After the existing `private val` declarations (after line 16), insert:

```scala
  private val tabs = List(
    ("ai",       "AI Models"),
    ("channels", "Channels"),
    ("gateway",  "Gateway"),
    ("system",   "System"),
    ("advanced", "Advanced Config"),
  )

  def settingsShell(activeTab: String, pageTitle: String)(bodyContent: Frag*): String =
    Layout.page(pageTitle, s"/settings/$activeTab")(
      div(cls := "mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Settings"),
      ),
      div(cls := "border-b border-white/10 mb-6")(
        nav(cls := "-mb-px flex space-x-6", attr("aria-label") := "Settings tabs")(
          tabs.map { case (tab, label) =>
            val isActive = tab == activeTab
            a(
              href := s"/settings/$tab",
              cls  :=
                (if isActive then
                   "border-b-2 border-indigo-500 py-4 px-1 text-sm font-medium text-white whitespace-nowrap"
                 else
                   "border-b-2 border-transparent py-4 px-1 text-sm font-medium text-gray-400 hover:text-white hover:border-white/30 whitespace-nowrap"),
              if isActive then attr("aria-current") := "page" else (),
            )(label)
          }
        )
      ),
      div(bodyContent*)
    )
```

Note: `nav` is imported from `scalatags.Text.tags2.nav` — it's already imported in `Layout.scala`; add the same import in `SettingsView.scala`:

```scala
import scalatags.Text.tags2.nav
```

**Step 2: Compile**

```bash
sbt compile
```

**Step 3: Commit**

```bash
git add src/main/scala/shared/web/SettingsView.scala
git commit -m "feat: add settingsShell tab bar helper to SettingsView"
```

---

## Task 3: AI Models Tab (`/settings/ai`)

**Goal:** The AI Models tab combines the AI Provider form section (from current Settings) with the Models table (from current ModelsView).

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`
- Modify: `src/main/scala/config/boundary/SettingsController.scala`

**Step 1: Add `aiTab` method to `SettingsView`**

In `SettingsView.scala`, add a new public method (after `settingsShell`):

```scala
  def aiTab(
    settings: Map[String, String],
    registry: config.control.ModelRegistryResponse,
    statuses: List[config.control.ProviderProbeStatus],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    val statusMap = statuses.map(s => s.provider -> s).toMap
    settingsShell("ai", "Settings — AI Models")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      if errors.nonEmpty then
        div(cls := "mb-6 rounded-md bg-red-500/10 border border-red-500/30 p-4")(
          p(cls := "text-sm font-semibold text-red-400")("Validation Errors"),
          ul(cls := "text-xs text-red-300 mt-2 space-y-1")(
            errors.map { case (key, msg) => li(s"$key: $msg") }.toSeq*
          ),
        )
      else (),
      // Provider config form (reuse existing aiProviderSection)
      tag("form")(method := "post", action := "/settings/ai", cls := "space-y-6 max-w-2xl mb-10")(
        aiProviderSection(settings, errors),
        div(cls := "flex gap-4 pt-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save AI Settings")
        ),
      ),
      // Models table
      h2(cls := "text-lg font-semibold text-white mb-4")("Available Models"),
      p(cls := "text-sm text-slate-300 mb-4")(
        "Models grouped by provider. Configure primary model and fallback chain above."
      ),
      div(cls := "space-y-4")(
        registry.providers.map { group =>
          val status = statusMap.get(group.provider)
          div(cls := "rounded-lg border border-white/10 bg-slate-900/70 p-5")(
            div(cls := "mb-3 flex items-center justify-between")(
              h3(cls := "text-lg font-semibold text-white")(group.provider.toString),
              ModelsView.statusBadge(status),
            ),
            p(cls := "mb-3 text-xs text-slate-400")(status.map(_.statusMessage).getOrElse("No health probe available")),
            table(cls := "min-w-full text-left text-sm text-slate-200")(
              thead(
                tr(
                  th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Model"),
                  th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Context"),
                  th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Capabilities"),
                )
              ),
              tbody(
                group.models.map { model =>
                  tr(cls := "border-t border-white/5")(
                    td(cls := "py-2 pr-4 font-mono text-xs")(model.modelId),
                    td(cls := "py-2 pr-4")(model.contextWindow.toString),
                    td(cls := "py-2 pr-4")(model.capabilities.toList.map(_.toString).sorted.mkString(", ")),
                  )
                }
              ),
            ),
          )
        }
      ),
    )
```

**Step 2: Make `ModelsView.statusBadge` accessible**

In `ModelsView.scala`, change `private def statusBadge` to `def statusBadge` (remove `private`):

```scala
  def statusBadge(status: Option[ProviderProbeStatus]): Frag =
```

**Step 3: Add `aiTabPage` to `HtmlViews`**

In `HtmlViews.scala`, add after `settingsPage`:

```scala
  def settingsAiTab(
    settings: Map[String, String],
    registry: config.control.ModelRegistryResponse,
    statuses: List[config.control.ProviderProbeStatus],
    flash: Option[String] = None,
  ): String =
    SettingsView.aiTab(settings, registry, statuses, flash)
```

**Step 4: Add routes in `SettingsController`**

In `SettingsControllerLive.routes`, add these two routes (after the existing `Method.GET / "settings"` and `Method.POST / "settings"` entries):

```scala
Method.GET / "settings" / "ai"  -> handler {
  ErrorHandlingMiddleware.fromPersistence {
    for
      rows    <- repository.getAllSettings
      settings = rows.map(r => r.key -> r.value).toMap
      models  <- modelService.listAvailableModels
      status  <- modelService.probeProviders
    yield html(HtmlViews.settingsAiTab(settings, models, status))
  }
},
Method.POST / "settings" / "ai" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form    <- parseForm(req)
      _       <- ZIO.foreachDiscard(settingsKeys.filter(_.startsWith("ai."))) { key =>
                   val value = form.getOrElse(key, "")
                   if value.nonEmpty then repository.upsertSetting(key, value) else ZIO.unit
                 }
      _       <- checkpointConfigStore
      rows    <- repository.getAllSettings
      saved    = rows.map(r => r.key -> r.value).toMap
      newConfig = SettingsApplier.toGatewayConfig(saved)
      _       <- configRef.set(newConfig)
      _       <- writeSettingsSnapshot(saved)
      now     <- Clock.instant
      _       <- activityHub.publish(
                   ActivityEvent(
                     id = EventId.generate,
                     eventType = ActivityEventType.ConfigChanged,
                     source = "settings.ai",
                     summary = "AI settings updated",
                     createdAt = now,
                   )
                 )
      models  <- modelService.listAvailableModels
      status  <- modelService.probeProviders
    yield html(HtmlViews.settingsAiTab(saved, models, status, Some("AI settings saved.")))
  }
},
```

**Step 5: Compile and verify**

```bash
sbt compile
```

**Step 6: Commit**

```bash
git add src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/shared/web/ModelsView.scala \
        src/main/scala/shared/web/HtmlViews.scala \
        src/main/scala/config/boundary/SettingsController.scala
git commit -m "feat: add /settings/ai tab combining AI provider config and models table"
```

---

## Task 4: Channels Tab (`/settings/channels`) with Inline Config

**Goal:** Move channel status + inline per-channel config into `/settings/channels`. Each card shows a `[Configure ▼]` toggle that fetches and inlines the config form for that channel.

**Files:**
- Modify: `src/main/scala/shared/web/ChannelView.scala`
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`
- Modify: `src/main/scala/gateway/boundary/ChannelController.scala`

**Step 1: Add channel inline config form methods to `ChannelView`**

In `ChannelView.scala`, add these methods:

```scala
  // Config form for a specific channel, returned as HTML fragment
  def channelConfigForm(name: String, settings: Map[String, String]): Frag =
    val formId = s"config-form-$name"
    div(id := formId, cls := "mt-4 border-t border-white/10 pt-4")(
      name match
        case "telegram"  => telegramConfigForm(settings)
        case "discord"   => discordConfigForm(settings)
        case "slack"     => slackConfigForm(settings)
        case "websocket" => p(cls := "text-xs text-gray-400")("WebSocket is built-in and requires no configuration.")
        case _           => p(cls := "text-xs text-gray-400")(s"No configuration available for $name.")
    )

  private def telegramConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/telegram",
      attr("hx-target")   := "#config-form-telegram",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(
            `type`       := "password",
            name         := "telegram.botToken",
            value        := settings.getOrElse("telegram.botToken", ""),
            placeholder  := "123456:ABC-token",
            cls          := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 focus:ring-2 focus:ring-indigo-500 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Mode"),
          select(
            name := "telegram.mode",
            id   := "telegram-mode-inline",
            cls  := "block w-full rounded-md bg-gray-900 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          )(
            option(value := "Polling", if settings.getOrElse("telegram.mode", "Polling") == "Polling" then selected := true else ())("Polling"),
            option(value := "Webhook", if settings.getOrElse("telegram.mode", "Polling") == "Webhook" then selected := true else ())("Webhook"),
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Poll Interval (s)"),
          input(
            `type` := "number",
            name   := "telegram.polling.interval",
            value  := settings.getOrElse("telegram.polling.interval", "1"),
            min    := "1", max := "60",
            cls    := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Batch Size"),
          input(
            `type` := "number",
            name   := "telegram.polling.batchSize",
            value  := settings.getOrElse("telegram.polling.batchSize", "100"),
            min    := "1", max := "1000",
            cls    := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
      ),
      div(cls := "flex items-center gap-3")(
        button(
          `type` := "submit",
          cls    := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500",
        )("Save"),
        span(id := s"save-status-telegram", cls := "text-xs text-gray-400")(""),
      ),
    )

  private def discordConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/discord",
      attr("hx-target")   := "#config-form-discord",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(`type` := "password", name := "botToken", placeholder := "Bot token",
            cls := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3"),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Guild ID (optional)"),
          input(`type` := "text", name := "guildId", placeholder := "Server ID",
            cls := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3"),
        ),
      ),
      button(`type` := "submit",
        cls := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500")("Save"),
    )

  private def slackConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/slack",
      attr("hx-target")   := "#config-form-slack",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("App Token"),
          input(`type` := "password", name := "appToken", placeholder := "xapp-...",
            cls := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3"),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(`type` := "password", name := "botToken", placeholder := "xoxb-...",
            cls := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3"),
        ),
      ),
      button(`type` := "submit",
        cls := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500")("Save"),
    )
```

**Step 2: Update `channelCard` to add the inline config toggle**

Replace the existing `channelCard` private method with:

```scala
  private def channelCard(card: ChannelCardData, nowMs: Long, settings: Map[String, String] = Map.empty): Frag =
    val (pillLabel, pillClasses) = statusPill(card.status)
    val configId                 = s"config-panel-${card.name}"
    div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-5")(
      div(cls := "flex items-center justify-between mb-3")(
        h2(cls := "text-lg font-semibold text-white capitalize")(card.name),
        span(cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset $pillClasses")(pillLabel),
      ),
      div(cls := "text-sm text-gray-300 space-y-1")(
        card.mode.map(mode => p(span(cls := "text-gray-400")("Mode: "), mode)),
        p(span(cls := "text-gray-400")("Active connections: "), card.activeConnections.toString),
        p(
          span(cls := "text-gray-400")("↑ "), card.messagesReceived.toString,
          raw("&nbsp;&nbsp;"),
          span(cls := "text-gray-400")("↓ "), card.messagesSent.toString,
          raw("&nbsp;&nbsp;"),
          span(cls := "text-gray-400")("Errors: "), card.errors.toString,
          raw("&nbsp;&nbsp;"),
          span(cls := "text-gray-400")("Last: "), relativeTime(card.lastActivityTs, nowMs),
        ),
      ),
      div(cls := "mt-4 flex justify-end")(
        button(
          cls              := "text-xs font-medium text-indigo-400 hover:text-indigo-300",
          attr("hx-get")   := s"/settings/channels/${card.name}/config-form",
          attr("hx-target"):= s"#$configId",
          attr("hx-swap")  := "innerHTML",
          attr("hx-push-url") := "false",
        )("Configure ▾"),
      ),
      div(id := configId)(),
    )
```

Also update `cardsFragment` signature to pass settings:

```scala
  def cardsFragment(cards: List[ChannelCardData], nowMs: Long, settings: Map[String, String] = Map.empty): Frag =
    if cards.isEmpty then Components.emptyState("No channels registered.")
    else
      div(cls := "grid grid-cols-1 gap-4 lg:grid-cols-2")(
        cards.sortBy(_.name).map(card => channelCard(card, nowMs, settings))
      )
```

**Step 3: Add `channelsTab` to `SettingsView`**

```scala
  def channelsTab(
    cards: List[ChannelCardData],
    nowMs: Long,
    settings: Map[String, String],
    flash: Option[String] = None,
  ): String =
    settingsShell("channels", "Settings — Channels")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      div(cls := "flex items-center justify-between mb-4")(
        p(cls := "text-sm text-gray-400")("Live channel status and configuration. Auto-refresh every 10 seconds."),
        a(
          href := "/api/channels/status",
          cls  := "inline-flex items-center rounded-md bg-white/5 px-3 py-1.5 text-xs font-semibold text-gray-300 ring-1 ring-white/10 hover:bg-white/10",
        )("Status API ↗"),
      ),
      // Add channel form
      div(cls := "mb-6 rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-wide text-gray-400 mb-3")("Add Channel"),
        div(cls := "flex items-center gap-3")(
          tag("form")(
            cls                 := "flex items-center gap-2 flex-1",
            attr("hx-post")     := "/api/channels/add",
            attr("hx-target")   := "#channels-cards",
            attr("hx-swap")     := "innerHTML",
            attr("hx-encoding") := "application/x-www-form-urlencoded",
          )(
            select(name := "name", cls := "rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10")(
              option(value := "discord")("Discord"),
              option(value := "slack")("Slack"),
              option(value := "websocket")("WebSocket"),
            ),
            input(
              `type`      := "password",
              name        := "botToken",
              placeholder := "Bot / App token",
              cls         := "flex-1 rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10",
            ),
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-500",
            )("Add"),
          ),
        ),
      ),
      // Channel cards (auto-refresh)
      div(
        id                   := "channels-cards",
        attr("hx-get")       := "/settings/channels/cards",
        attr("hx-trigger")   := "every 10s",
        attr("hx-swap")      := "innerHTML",
        attr("hx-indicator") := "#channels-refresh-indicator",
      )(ChannelView.cardsFragment(cards, nowMs, settings)),
      div(id := "channels-refresh-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-3")("Refreshing..."),
    )
```

**Step 4: Add to `HtmlViews`**

```scala
  def settingsChannelsTab(
    cards: List[ChannelCardData],
    nowMs: Long,
    settings: Map[String, String],
    flash: Option[String] = None,
  ): String =
    SettingsView.channelsTab(cards, nowMs, settings, flash)
```

**Step 5: Add routes to `ChannelController`**

In `ChannelControllerLive.routes`, add:

```scala
// Settings tab: channels view (under /settings/channels)
Method.GET / "settings" / "channels"                                -> handler {
  buildChannelsTabPage(None)
},
Method.GET / "settings" / "channels" / "cards"                     -> handler {
  buildCards.flatMap(cards =>
    Clock.currentTime(TimeUnit.MILLISECONDS).flatMap(now =>
      getSettings.map(settings => htmlFragment(ChannelView.cardsFragment(cards, now, settings).render))
    )
  )
},
// Per-channel inline config form (HTMX fragment)
Method.GET / "settings" / "channels" / string("name") / "config-form" -> handler { (name: String, _: Request) =>
  getSettings.map(settings => htmlFragment(ChannelView.channelConfigForm(name, settings).render))
},
// Save per-channel config
Method.POST / "settings" / "channels" / string("name")             -> handler { (name: String, req: Request) =>
  ErrorHandling.fromPersistence {
    for
      form     <- parseForm(req)
      prefixed  = form.map { case (k, v) => (s"channel.$name.$k" -> v) } ++
                    form.filter { case (k, _) => k.startsWith(s"$name.") || k.startsWith("telegram.") }
      _        <- configRepository.upsertSettings(prefixed)
      settings <- getSettings
    yield htmlFragment(ChannelView.channelConfigForm(name, settings).render)
  }
},
```

Also add `getSettings` helper to `ChannelControllerLive`:

```scala
private def getSettings: UIO[Map[String, String]] =
  configRepository.getAllSettings
    .map(_.map(r => r.key -> r.value).toMap)
    .catchAll(_ => ZIO.succeed(Map.empty))

private def buildChannelsTabPage(flash: Option[String]): UIO[Response] =
  for
    cards    <- buildCards
    now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
    settings <- getSettings
  yield html(HtmlViews.settingsChannelsTab(cards, now, settings, flash))
```

**Step 6: Compile**

```bash
sbt compile
```

**Step 7: Commit**

```bash
git add src/main/scala/shared/web/ChannelView.scala \
        src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/shared/web/HtmlViews.scala \
        src/main/scala/gateway/boundary/ChannelController.scala
git commit -m "feat: add /settings/channels tab with inline per-channel config forms"
```

---

## Task 5: Gateway Tab (`/settings/gateway`)

**Goal:** Move gateway + memory + danger-zone sections into `/settings/gateway` tab.

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`
- Modify: `src/main/scala/config/boundary/SettingsController.scala`

**Step 1: Add `gatewayTab` to `SettingsView`**

```scala
  def gatewayTab(
    settings: Map[String, String],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    settingsShell("gateway", "Settings — Gateway")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      tag("form")(method := "post", action := "/settings/gateway", cls := "space-y-6 max-w-2xl")(
        gatewaySection(settings, errors),
        telegramSection(settings, errors),  // Telegram stays here too for full config
        memorySection(settings, errors),
        div(cls := "flex gap-4 pt-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save Gateway Settings")
        ),
      ),
      div(cls := "mt-10 rounded-lg border border-red-500/30 bg-red-950/30 p-5 max-w-2xl")(
        h3(cls := "text-lg font-semibold text-red-200")("Reset Operational Data"),
        p(cls := "mt-2 text-sm text-red-100/90")(
          "Deletes all tasks, conversations, activity logs, and memory. Configuration is preserved."
        ),
        button(
          `type`             := "button",
          cls                := "mt-4 rounded-md border border-red-400/40 bg-red-500/20 px-4 py-2 text-sm font-semibold text-red-100 hover:bg-red-500/30",
          attr("hx-post")    := "/api/store/reset-data",
          attr("hx-confirm") := "This will permanently delete all tasks and conversations. Are you sure?",
          attr("hx-swap")    := "none",
        )("Reset Data Store"),
      ),
    )
```

**Step 2: Add to `HtmlViews`**

```scala
  def settingsGatewayTab(settings: Map[String, String], flash: Option[String] = None): String =
    SettingsView.gatewayTab(settings, flash)
```

**Step 3: Add routes to `SettingsController`**

```scala
Method.GET / "settings" / "gateway"  -> handler {
  ErrorHandlingMiddleware.fromPersistence {
    for
      rows    <- repository.getAllSettings
      settings = rows.map(r => r.key -> r.value).toMap
    yield html(HtmlViews.settingsGatewayTab(settings))
  }
},
Method.POST / "settings" / "gateway" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form    <- parseForm(req)
      keys     = settingsKeys.filter(k => k.startsWith("gateway.") || k.startsWith("telegram.") || k.startsWith("memory."))
      _       <- ZIO.foreachDiscard(keys) { key =>
                   val value = key match
                     case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" | "memory.enabled" =>
                       if form.get(key).exists(_.equalsIgnoreCase("on")) then "true" else "false"
                     case _ => form.getOrElse(key, "")
                   if value.nonEmpty then repository.upsertSetting(key, value) else ZIO.unit
                 }
      _       <- checkpointConfigStore
      rows    <- repository.getAllSettings
      saved    = rows.map(r => r.key -> r.value).toMap
      newConfig = SettingsApplier.toGatewayConfig(saved)
      _       <- configRef.set(newConfig)
      _       <- writeSettingsSnapshot(saved)
      now     <- Clock.instant
      _       <- activityHub.publish(
                   ActivityEvent(
                     id = EventId.generate,
                     eventType = ActivityEventType.ConfigChanged,
                     source = "settings.gateway",
                     summary = "Gateway settings updated",
                     createdAt = now,
                   )
                 )
    yield html(HtmlViews.settingsGatewayTab(saved, Some("Gateway settings saved.")))
  }
},
```

**Step 4: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/shared/web/HtmlViews.scala \
        src/main/scala/config/boundary/SettingsController.scala
git commit -m "feat: add /settings/gateway tab with gateway, telegram, memory, danger zone"
```

---

## Task 6: System Tab (`/settings/system`)

**Goal:** Embed health dashboard into `/settings/system`.

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`
- Modify: `src/main/scala/app/boundary/HealthController.scala`

**Step 1: Add `systemTab` to `SettingsView`**

```scala
  def systemTab: String =
    settingsShell("system", "Settings — System")(
      div(cls := "mb-4")(
        p(cls := "text-sm text-gray-400")("Real-time gateway, agent, channel, and resource telemetry."),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("health-dashboard")(
          attr("ws-url") := "/ws/console"
        )()
      ),
      JsResources.inlineModuleScript("/static/client/components/health-dashboard.js"),
    )
```

**Step 2: Add to `HtmlViews`**

```scala
  def settingsSystemTab: String = SettingsView.systemTab
```

**Step 3: Add route to `HealthController`**

In `HealthController`, add this route:

```scala
Method.GET / "settings" / "system" -> handler {
  ZIO.succeed(html(HtmlViews.settingsSystemTab))
},
```

**Step 4: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/shared/web/HtmlViews.scala \
        src/main/scala/app/boundary/HealthController.scala
git commit -m "feat: add /settings/system tab embedding health dashboard"
```

---

## Task 7: Advanced Config Tab (`/settings/advanced`)

**Goal:** Move config editor into `/settings/advanced`.

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`
- Modify: `src/main/scala/config/boundary/ConfigController.scala`

**Step 1: Add `advancedTab` to `SettingsView`**

```scala
  def advancedTab: String =
    settingsShell("advanced", "Settings — Advanced Config")(
      div(cls := "mb-4 rounded-md bg-amber-500/10 border border-amber-500/20 p-3")(
        p(cls := "text-sm text-amber-300")(
          "⚠️ Advanced users only. Most settings are configurable via the tabs above with validation and helper text."
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("config-editor")(attr("api-base") := "/api/config")(),
      ),
      script(src := "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"),
      link(
        attr("rel") := "stylesheet",
        href        := "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/config-editor.js"),
    )
```

**Step 2: Add to `HtmlViews`**

```scala
  def settingsAdvancedTab: String = SettingsView.advancedTab
```

**Step 3: Add route to `ConfigController`**

In `ConfigController.routes`, add:

```scala
Method.GET / "settings" / "advanced" -> handler {
  ZIO.succeed(html(HtmlViews.settingsAdvancedTab))
},
```

**Step 4: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/shared/web/HtmlViews.scala \
        src/main/scala/config/boundary/ConfigController.scala
git commit -m "feat: add /settings/advanced tab embedding config editor"
```

---

## Task 8: Redirects for Legacy Routes

**Goal:** Old URLs (`/settings`, `/config`, `/models`, `/channels`, `/health`) redirect to correct `/settings/:tab`.

**Files:**
- Modify: `src/main/scala/config/boundary/SettingsController.scala`
- Modify: `src/main/scala/config/boundary/ConfigController.scala`
- Modify: `src/main/scala/gateway/boundary/ChannelController.scala`
- Modify: `src/main/scala/app/boundary/HealthController.scala`

**Step 1: `SettingsController` — redirect `/settings` and `/models`**

Replace the existing `Method.GET / "settings"` handler with a redirect:

```scala
Method.GET / "settings" -> handler {
  ZIO.succeed(Response.redirect(URL.decode("/settings/ai").toOption.get))
},
Method.GET / "models"   -> handler {
  ZIO.succeed(Response.redirect(URL.decode("/settings/ai").toOption.get))
},
```

Note: `Response.redirect` in ZIO HTTP takes a `URL`. Use `URL.decode("/settings/ai").getOrElse(...)`.

Actually in ZIO HTTP 2.x the redirect is:

```scala
ZIO.succeed(Response(status = Status.Found, headers = Headers(Header.Location(URL.decode("/settings/ai").toOption.get))))
```

**Step 2: `ConfigController` — redirect `/config`**

Replace the existing `Method.GET / "config"` handler:

```scala
Method.GET / "config" -> handler {
  ZIO.succeed(Response(status = Status.Found, headers = Headers(Header.Location(URL.decode("/settings/advanced").toOption.get))))
},
```

**Step 3: `ChannelController` — redirect `/channels`**

Replace the existing `Method.GET / "channels"` handler:

```scala
Method.GET / "channels" -> handler {
  ZIO.succeed(Response(status = Status.Found, headers = Headers(Header.Location(URL.decode("/settings/channels").toOption.get))))
},
```

Keep the HTMX fragment routes (`/channels/cards`, `/channels/summary`) as-is — they're used by dashboard and other pages.

**Step 4: `HealthController` — redirect `/health`**

Replace the existing `Method.GET / "health"` handler:

```scala
Method.GET / "health" -> handler {
  ZIO.succeed(Response(status = Status.Found, headers = Headers(Header.Location(URL.decode("/settings/system").toOption.get))))
},
```

**Step 5: Compile**

```bash
sbt compile
```

**Step 6: Commit**

```bash
git add src/main/scala/config/boundary/SettingsController.scala \
        src/main/scala/config/boundary/ConfigController.scala \
        src/main/scala/gateway/boundary/ChannelController.scala \
        src/main/scala/app/boundary/HealthController.scala
git commit -m "feat: redirect /settings /config /models /channels /health to /settings/:tab"
```

---

## Task 9: Add `+ New Task` Button to Tasks Page

**Goal:** Remove `New Task` from nav (already done in Task 1); add a `+ New Task` button to the Tasks list page header.

**Files:**
- Modify: `src/main/scala/shared/web/TasksView.scala`

**Step 1: Add the button to `tasksList` header**

In `TasksView.tasksList`, replace:

```scala
h1(cls := "text-2xl font-bold text-white mb-6")("Tasks"),
```

with:

```scala
div(cls := "flex items-center justify-between mb-6")(
  h1(cls := "text-2xl font-bold text-white")("Tasks"),
  a(
    href := "/tasks/new",
    cls  := "rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-500",
  )("+ New Task"),
),
```

**Step 2: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/TasksView.scala
git commit -m "feat: add + New Task button to tasks list header"
```

---

## Task 10: Add Graph Link to Task Detail

**Goal:** Reports already has a link on task detail (line 71 in TasksView). Add Graph alongside it.

**Files:**
- Modify: `src/main/scala/shared/web/TasksView.scala`

**Step 1: Add graph link next to existing reports link**

On line 71–73 of `TasksView.scala`:

```scala
a(href := s"/reports?taskId=${run.id}", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")(
  "View Reports"
),
```

Add after it:

```scala
a(href := s"/graph?taskId=${run.id}", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")(
  "View Graph"
),
```

**Step 2: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/TasksView.scala
git commit -m "feat: add View Graph link to task detail page"
```

---

## Task 11: Update Dashboard Channels Widget Link

**Goal:** The channels summary widget on dashboard currently links to `/channels` (line 123 in `ChannelView.scala`). Update to `/settings/channels`.

**Files:**
- Modify: `src/main/scala/shared/web/ChannelView.scala`

**Step 1: Update the `summaryWidgetFragment` link**

In `ChannelView.summaryWidgetFragment`, replace:

```scala
a(href := "/channels", cls := "text-xs text-indigo-400 hover:text-indigo-300")("Open"),
```

with:

```scala
a(href := "/settings/channels", cls := "text-xs text-indigo-400 hover:text-indigo-300")("Open"),
```

Also update the auto-refresh HTMX poll endpoint. In `SettingsView.channelsTab`, the cards already poll `/settings/channels/cards`. Verify the `DashboardView` `channels/summary` HTMX target still points to `/channels/summary` (that endpoint is kept for the dashboard widget).

**Step 2: Compile and commit**

```bash
sbt compile
git add src/main/scala/shared/web/ChannelView.scala
git commit -m "fix: update channels summary widget link to /settings/channels"
```

---

## Task 12: Final Smoke Test

**Step 1: Start the app**

```bash
sbt run
```

Or if using the serve command:

```bash
sbt "run serve --port 8080"
```

**Step 2: Verify navigation**

Open `http://localhost:8080` and check:
- [ ] Sidebar has 10 items: Dashboard, Tasks, Workflows | Chat, Issues, Activity, Memory | Agents, Agent Monitor | Settings
- [ ] No Reports, Graph, New Task, Config, Models, Channels, Health in sidebar
- [ ] "Settings" nav item is active when on any `/settings/*`, `/config`, `/models`, `/channels`, `/health` URL

**Step 3: Verify settings tabs**

- [ ] `/settings` redirects to `/settings/ai`
- [ ] `/settings/ai` shows AI provider form + models table with tabs at top
- [ ] `/settings/channels` shows channel cards, [Configure ▾] toggles load inline config
- [ ] `/settings/gateway` shows gateway + telegram + memory form
- [ ] `/settings/system` shows health dashboard
- [ ] `/settings/advanced` shows config editor

**Step 4: Verify redirects**

- [ ] `/config` → 302 → `/settings/advanced`
- [ ] `/models` → 302 → `/settings/ai`
- [ ] `/channels` → 302 → `/settings/channels`
- [ ] `/health` → 302 → `/settings/system`

**Step 5: Verify demoted pages**

- [ ] `/tasks` has `+ New Task` button at top right
- [ ] Task detail has both `View Reports` and `View Graph` links

**Step 6: Commit final polish if any, then push**

```bash
git push
```

---

## Summary

| Task | Scope | Files |
|------|-------|-------|
| 1 | Slim sidebar | `Layout.scala` |
| 2 | Settings shell + tab bar | `SettingsView.scala` |
| 3 | AI Models tab | `SettingsView`, `ModelsView`, `HtmlViews`, `SettingsController` |
| 4 | Channels tab + inline config | `ChannelView`, `SettingsView`, `HtmlViews`, `ChannelController` |
| 5 | Gateway tab | `SettingsView`, `HtmlViews`, `SettingsController` |
| 6 | System tab | `SettingsView`, `HtmlViews`, `HealthController` |
| 7 | Advanced Config tab | `SettingsView`, `HtmlViews`, `ConfigController` |
| 8 | Redirects | `SettingsController`, `ConfigController`, `ChannelController`, `HealthController` |
| 9 | + New Task button | `TasksView` |
| 10 | View Graph link on task detail | `TasksView` |
| 11 | Dashboard widget link | `ChannelView` |
| 12 | Smoke test | — |
