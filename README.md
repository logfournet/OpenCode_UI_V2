# OpenCode UI — JetBrains Plugin

Bring the **OpenCode** AI coding agent into IntelliJ IDEA, PyCharm, and other
JetBrains IDEs. The plugin hosts the OpenCode TUI in a docked side panel so the
code editor stays visible next to the agent — the familiar
**file tree | code | agent** layout.

> **Marketplace:** <https://plugins.jetbrains.com/plugin/29744-opencode-ui>
>
> **Author:** logatic

---

## Overview

This plugin connects the IDE to the **OpenCode 2.x background service**
(`opencode serve --service`). Instead of spawning its own server per project
(which caused the old *"server dead"* problem), it discovers and connects to the
single running service via `service.json`, with automatic reconnection when the
service restarts.

- **One service, many projects** — multiple IDEs / projects share one background
  service; each project gets its own session.
- **Editor stays visible** — the agent lives in a docked panel, not an editor
  tab.

---

## Features

### Docked OpenCode panel
- Click the **OpenCode** icon (or press `Ctrl+\`, macOS `Cmd+Esc`) to open the
  panel on the right side.
- The OpenCode TUI runs inside a real terminal widget, connected to the
  background service (session is scoped to the project directory).

### Service discovery & reconnection
- Reads `service.json` to find the running service URL + password (Basic Auth):
  - Windows: `%LOCALAPPDATA%\opencode\state\service.json`
  - Other: `~/.local/state/opencode/service.json`
- **Auto-connect** on project open (silent; never shows a dialog if the service
  is not found).
- **Auto-reconnect** after repeated poll failures — no manual "reconnect" needed
  when the service restarts.
- Manual connection dialog available if discovery fails.

### Send code to the agent
- **`Ctrl+Alt+K`** — send the current file/selection as context:
  `@path` or `@path#Lx-y` (pasted into the TUI input).
- **Right-click → OpenCode** submenu with one-click code actions:
  - **Explain**, **Fix**, **Refactor**, **Add Tests**, **Optimize**, **Document**
  - **Ask OpenCode…** — free-form prompt dialog
  - These type the prompt and press Enter automatically, so the request starts
    immediately.
- **Drag & drop** files onto the panel to insert `@path` references.

### Clickable file links
- `@path`, `@"path with spaces"`, and `@path#Lx-y` inside the terminal are
  clickable — clicking opens the file and highlights/scrolls to the lines.

### Diff viewer with Accept / Reject
- When a turn finishes, the plugin fetches the diff
  (`GET /api/session/{id}/diff?from=<userMsgId>`) and opens IntelliJ's native
  multi-file diff viewer.
- Per-file **Accept** (write + `git add`) and **Reject** (restore the "before"
  content, reconstructed by reverse-applying the unified patch).
- Newly created files are picked up via a VFS refresh + patch fallback.

### Status bar & notifications
- **Status bar widget** shows the connection state and an animated spinner while
  a turn is running; click to open the panel.
- **Completion notification** when the agent finishes, including the number of
  changed files and a **Review Diffs** action.

### Tool window actions
The panel's title bar provides quick actions:
- **New Session** — start a fresh session (`/new`).
- **Stop** — interrupt the current generation.
- **Review Diffs** — reopen the last turn's diff.
- **Copy Output as Markdown** — copy the whole terminal buffer as a fenced code
  block.

### ESC interrupts the turn
- Pressing `Esc` in the panel stops the running generation (the plugin consumes
  the key before IntelliJ's *jump-to-editor* escape handler and forwards it to
  the TUI's `session.interrupt` binding).

### Settings
- **Settings → Tools → OpenCode**
  - Custom path to the `opencode` binary (blank = auto-detect).
  - Toggle auto-connect on project open.
  - **Tab scope** — `Per project (cwd)` (default) keeps the TUI session tabs
    per working directory; `Global (shared)` restores OpenCode v2's global tab
    strip.
  - **Terminal scrollback** — keeps the whole terminal history by default
    (**Unlimited**, no line limit). Untick *Unlimited* to impose a finite cap
    (e.g. `100000` lines). Applied to the IDE setting
    `terminal.buffer.max.lines.count` (shared by all IDE terminals).

### Per-project session tabs (tab leak fix)
OpenCode v2 keeps its TUI tab strip in **one global client config**
(`~/.config/opencode/cli.json`). Because the plugin hosts the TUI in a
terminal, running it in two IDE windows at once (e.g. PyCharm and IntelliJ)
made the tabs "jump" between them — both windows read/wrote the same global
tab state and showed the same, overlapping tabs instead of each having its own.

The plugin fixes this at launch time: it writes a small wrapper script
(`~/.config/opencode/opencode-launch.cmd|.sh`) that exports

```
OPENCODE_CLI_CONFIG_CONTENT={"tabs":{"scope":"cwd"}}
```

so the TUI keeps its tabs per working directory. `OPENCODE_CLI_CONFIG_CONTENT`
injects **only** CLI/UI settings and stays authoritative while set, so it does
**not** relocate OpenCode's config directory — your `opencode.json`, agents,
commands, plugins and credentials are untouched.

This is the closest v2 equivalent of v1's per-project server isolation. Disable
it (set **Tab scope** to `Global (shared)`) if you prefer the global OpenCode
tab strip.

> Note: two IDE windows open on the **same** directory still share one tab
> strip (they resolve to the same `cwd`). That is OpenCode v2's `tabs.scope`
> behaviour; use different checkouts/worktrees to get independent tabs.

### RAM usage & scrollback
JediTerm keeps a terminal history buffer that is capped by the IDE advanced
setting `terminal.buffer.max.lines.count` (JetBrains' default: 5000 lines) and
automatically evicts the oldest lines once the cap is reached. Earlier plugin
versions additionally wiped the entire scrollback every couple of seconds,
which caused visible stutter while the agent produced a lot of output; that was
removed in **2.0.2**.

From **2.0.3** there is **no line limit by default**: the **Terminal
scrollback** setting removes the cap
(`terminal.buffer.max.lines.count`) before the TUI terminal is created, so long
agent output is kept and can still be scrolled back. The history is backed by a
dynamic deque that only grows with the lines that are actually produced and
never pre-allocates, so keeping everything costs nothing until the output is
really there. If you want a bound again, untick **Unlimited** and set a finite
value (e.g. `5000`).

---

## Requirements

- A JetBrains IDE **2024.2+** (build 242+) — IntelliJ IDEA, PyCharm, and other
  IDEs based on the IntelliJ Platform.
- The **Terminal** plugin (bundled by default).
- The **OpenCode CLI 2.x** installed and its background service running.

---

## Installation

### From the JetBrains Marketplace (recommended)
1. **Settings → Plugins → Marketplace**.
2. Search for **OpenCode UI** and install.
3. Restart the IDE.

Marketplace page: <https://plugins.jetbrains.com/plugin/29744-opencode-ui>

### From disk
1. Download `OpenCode_UI_V2-2.0.6.zip` from the
   [Releases](https://github.com/log4tic/OpenCode_UI_V2/releases) page.
2. **Settings → Plugins → ⚙️ → Install Plugin from Disk…** and pick the zip.
3. Restart the IDE.

### First run
1. Click the **OpenCode** icon (or press `Ctrl+\`).
   - The plugin reads `service.json`, connects to the running service, and opens
     the TUI.
2. Select code and press `Ctrl+Alt+K` to send context.
3. When OpenCode edits files, the diff viewer opens with Accept / Reject.

---

## Keyboard shortcuts

| Action | Shortcut |
| --- | --- |
| Open / focus the OpenCode panel | `Ctrl+\` (macOS `Cmd+Esc`) |
| Send file / selection as context | `Ctrl+Alt+K` |
| Interrupt the current turn | `Esc` (inside the panel) |
| Code actions (Explain / Fix / …) | Right-click → **OpenCode** |

---

## Building from source

Built with the **IntelliJ Platform Gradle Plugin 2.10.2** (JDK 17 target).

```powershell
cd newplugin
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
.\gradlew.bat buildPlugin
```

Output:

- `build\distributions\OpenCode_UI_V2-2.0.6.zip` — installable plugin
  (structure: `OpenCode_UI_V2/lib/*.jar`)
- `build\libs\OpenCode_UI_V2-2.0.6.jar`

Pre-built artifacts are attached to the
[Releases](https://github.com/log4tic/OpenCode_UI_V2/releases) page.

---

## Project structure

```
src/main/java/ai/opencode/ide/jetbrains/
  api/        OpenCodeApiClient (java.net.http + Gson), OpenCodeEventStream (SSE)
  patch/      UnifiedPatch   (reverse-apply unified diff -> "before" content)
  session/    SessionManager (snapshots files changed in a turn), FileDiff, SessionInfo
  diff/       DiffViewerService, DiffHandler (Accept/Reject), ResolvedDiff
  service/    OpenCodeService (connection, auto-reconnect, session tracking, TUI panel)
  ui/         OpenCodeToolWindowFactory, OpenCodeStatusBarWidget(+Factory),
              OpenCodeSettingsConfigurable, OpenCodeStartupActivity, OpenCodeConnectDialog
  terminal/   OpenCodeTerminalLinkFilter (clickable @path links)
  util/       ServerConnection, OpenCodeReference (@path#Lx-y builder)
  actions/    QuickLaunch, SendSelection, NewSession, Interrupt, OpenDiffs,
              CopyOutput, OpenCodeAsk, OpenCodeAskWithPrompt, OpenCodeActionsGroup
src/main/resources/META-INF/plugin.xml, icons/opencode.svg
```

---

## API notes (2.0.26, verified against a live server)

- Basic auth `opencode:<password>` (password from `service.json`); missing auth
  → `401`.
- Every `/api/*` endpoint wraps its payload in an envelope: `{"data": {...}}`.
- `POST /api/session/{id}/prompt` body `{"text": "..."}` (the `text` field is
  required); response `data.id` is the user message id.
- `GET /api/session/{id}/diff?from=<userMsgId>` → `data[]`:
  `{file, patch, additions, deletions, status: added|deleted|modified}` —
  there is no `before/after` (unlike v1), so the viewer reconstructs "before"
  from the patch.
- `GET /api/session/active` → `{"data": {"ses_...": {"type":"running"}}}`.
- SSE `/api/event`:
  `data: {"id":"evt_...","type":"session.tool.success","data":{...}}` — the
  stream is volatile, so the plugin reconnects and polls as a fallback.

---

## Links

- **JetBrains Marketplace:** <https://plugins.jetbrains.com/plugin/29744-opencode-ui>
- **OpenCode:** <https://opencode.ai>

---

## Author

Developed by **logatic**.
