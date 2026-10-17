package ai.opencode.ide.jetbrains.service;

import ai.opencode.ide.jetbrains.OpenCodePlugin;
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient;
import ai.opencode.ide.jetbrains.api.OpenCodeEventStream;
import ai.opencode.ide.jetbrains.diff.DiffViewerService;
import ai.opencode.ide.jetbrains.diff.ResolvedDiff;
import ai.opencode.ide.jetbrains.patch.UnifiedPatch;
import ai.opencode.ide.jetbrains.session.FileDiff;
import ai.opencode.ide.jetbrains.session.SessionInfo;
import ai.opencode.ide.jetbrains.session.SessionManager;
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter;
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog;
import ai.opencode.ide.jetbrains.ui.OpenCodeSettingsConfigurable;
import ai.opencode.ide.jetbrains.util.ServerConnection;
import com.google.gson.JsonObject;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jediterm.terminal.TerminalStarter;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;

/**
 * Central project service: discovers the OpenCode v2 background server, keeps an
 * SSE connection, tracks when the project's session finishes a turn, and surfaces
 * the /diff results through {@link DiffViewerService}.
 */
@Service(Service.Level.PROJECT)
public final class OpenCodeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(OpenCodeService.class);

    private final Project project;

    private volatile ServerConnection conn;
    private volatile OpenCodeApiClient api;
    private volatile OpenCodeEventStream events;
    private volatile boolean connected;
    private volatile boolean connecting;

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<ResolvedDiff> lastDiffs = new CopyOnWriteArrayList<>();

    /** Consecutive failed polls since the last successful one (drives auto-reconnect). */
    private volatile int consecutiveFailures;
    private static final int RECONNECT_AFTER_FAILURES = 3;
    private static final long RECONNECT_BACKOFF_MS = 5000;

    /**
     * IDE advanced setting that caps the terminal scrollback history
     * ({@code JBTerminalSystemSettingsProviderBase.getBufferMaxLinesCount()} reads
     * it and {@code TerminalTextBuffer} uses it as its {@code maxHistoryLinesCount};
     * JetBrains' default is 5000 lines).
     *
     * <p>The plugin drives it from the {@code opencode.terminalBufferLines} setting
     * so long agent output is no longer trimmed; by default the limit is removed
     * (the history storage only evicts once the count exceeds the cap and never
     * pre-allocates, so nothing is reserved up front). The key is shared with every
     * terminal in the IDE, so the value applies to all terminal tabs.
     */
    private static final String TERMINAL_MAX_LINES_KEY = "terminal.buffer.max.lines.count";

    private volatile String sessionId;
    private volatile String lastUserMsgId;
    private volatile boolean turnActive;
    private final AtomicBoolean turnChecked = new AtomicBoolean(false);

    // Terminal UI: the OpenCode TUI lives in a docked ToolWindow panel (not an
    // editor tab), so the code editor stays visible alongside the agent.
    private volatile ShellTerminalWidget terminalWidget;
    private volatile JPanel terminalPanel;
    private volatile String cachedBinary;
    private final AtomicBoolean terminalCommandStarted = new AtomicBoolean(false);
    /** When the opencode TUI process was launched (for boot-delay heuristics). */
    private volatile long terminalStartedAt;

    private final ScheduledExecutorService poller;

    public OpenCodeService(Project project) {
        this.project = project;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opencode-service");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Kick off connection discovery in the background. */
    public void connect() {
        if (connecting || connected || disposed.get()) return;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ServerConnection c = ServerConnection.discover();
                if (c != null && ServerConnection.isReachable(c, 1500)) {
                    OpenCodeApiClient probe = new OpenCodeApiClient(c.url, "opencode", c.password);
                    if (probe.info() != null) {
                        applyConnection(c);
                        return;
                    }
                }
                // Ask the user for a manual address (dialog on EDT, then connect in background).
                ApplicationManager.getApplication().invokeLater(() ->
                        OpenCodeConnectDialog.show(project, connResult -> {
                            if (connResult != null) {
                                AppExecutorUtil.getAppExecutorService()
                                        .execute(() -> applyConnection(connResult));
                            }
                        }));
            } finally {
                connecting = false;
            }
        });
    }

    /**
     * Connect on project open without ever showing the manual dialog. If the
     * background service is not discoverable we just stay idle; the user can
     * still open the panel / run {@link #connect()} manually.
     */
    public void autoConnect() {
        if (connected || connecting || disposed.get()) return;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ServerConnection c = ServerConnection.discover();
                if (c != null && ServerConnection.isReachable(c, 1500)) {
                    OpenCodeApiClient probe = new OpenCodeApiClient(c.url, "opencode", c.password);
                    if (probe.info() != null) applyConnection(c);
                }
            } finally {
                connecting = false;
                notifyChanged();
            }
        });
    }

    /** Apply a discovered or hand-entered connection. Returns true when connected. */
    public boolean applyConnection(ServerConnection c) {
        if (c == null || disposed.get()) return false;
        teardown();
        OpenCodeApiClient candidate = new OpenCodeApiClient(c.url, "opencode", c.password);
        String version = candidate.info();
        if (version == null) {
            connected = false;
            notify("Connection failed", "OpenCode server at " + c.display() + " did not respond.", NotificationType.ERROR);
            notifyChanged();
            return false;
        }
        this.conn = c;
        this.api = candidate;
        this.connected = true;
        this.consecutiveFailures = 0;
        lastUserMsgId = null;
        turnActive = false;
        discoverSession();
        startEventStream();
        startPolling();
        notify("Connected", "OpenCode server " + c.display() + " (v" + version + ")", NotificationType.INFORMATION);
        notifyChanged();
        return true;
    }

    public void teardown() {
        OpenCodeEventStream e = events;
        if (e != null) {
            events = null;
            try {
                e.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Re-run discovery (e.g. after the server restarts). */
    public void reconnect() {
        reconnectSilently();
    }

    // ------------------------------------------------------------------
    // Session selection
    // ------------------------------------------------------------------

    private void discoverSession() {
        if (api == null) return;
        String base = project.getBasePath();
        String match = null;
        List<JsonObject> sessions = api.listSessions(50, "desc");
        for (JsonObject s : sessions) {
            String dir = locationDirectory(s);
            if (base != null && dir != null && normalize(base).equalsIgnoreCase(normalize(dir))) {
                match = idOf(s);
                break;
            }
        }
        if (match == null && base == null && !sessions.isEmpty()) {
            match = idOf(sessions.get(0));
        }
        if (match == null) {
            match = api.createSession(project.getName(), base == null ? "" : base);
        }
        sessionId = match;
        lastUserMsgId = null;
    }

    private String idOf(JsonObject s) {
        return s.has("id") && !s.get("id").isJsonNull() ? s.get("id").getAsString() : null;
    }

    private String locationDirectory(JsonObject s) {
        if (s.has("location") && s.get("location").isJsonObject()
                && s.getAsJsonObject("location").has("directory")) {
            return s.getAsJsonObject("location").get("directory").getAsString();
        }
        return null;
    }

    private String normalize(String p) {
        if (p == null) return "";
        String v = p.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** Pick a different existing session (called from the tool window combo). */
    public void setSession(String id) {
        if (id == null || id.isBlank()) return;
        sessionId = id;
        lastUserMsgId = null;
        turnActive = false;
        notifyChanged();
    }

    public List<SessionInfo> listSessions() {
        if (api == null) return new ArrayList<>();
        List<SessionInfo> out = new ArrayList<>();
        for (JsonObject s : api.listSessions(50, "desc")) {
            try {
                out.add(SessionInfo.fromJson(s));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Terminal UI (OpenCode TUI docked in a ToolWindow panel)
    // ------------------------------------------------------------------

    /**
     * Show the OpenCode tool window and make sure the TUI is running inside it.
     * The TUI lives in its own dockable panel (right side by default) instead of
     * an editor tab, so the code editor stays visible while chatting with the
     * agent — the "files | code | agent" layout used by other AI plugins.
     */
    public void focusOrCreateTerminal() {
        if (project.isDisposed()) return;
        // Make sure the plugin's own connection is up so diffs / turn tracking work
        // while the user chats with the agent in the panel.
        if (!isConnected() && !connecting) connect();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                ToolWindow tw = getToolWindow();
                if (tw != null) tw.activate(null, false);
            } catch (Exception ignored) {
            }
        });
        // Create the widget + panel now if needed (idempotent). The tool window
        // factory uses the same panel when it attaches content.
        ensureTerminalOnEdt();
    }

    /** True when the OpenCode terminal widget has been created (panel exists). */
    public boolean hasTerminalUI() {
        return terminalWidget != null;
    }

    /**
     * Return (creating if needed) the JPanel hosting the OpenCode TUI, and attach
     * it as the tool window content the first time. Called by the tool window
     * factory / {@link #focusOrCreateTerminal()} on the EDT.
     */
    public synchronized JPanel ensureTerminal() {
        return ensureTerminal(getToolWindow());
    }

    /**
     * Return (creating if needed) the JPanel hosting the OpenCode TUI and make sure
     * it is the content of the given tool window.
     *
     * <p>In recent platform builds {@code TerminalView.createLocalShellWidget} no
     * longer returns a detached widget: it appends a tab to the global
     * <em>Terminal</em> tool window (and, when asked to focus, activates that tool
     * window). Re-parenting {@code widget.getComponent()} into our own panel then
     * left an empty tab in the Terminal window and let it steal the component back,
     * so the OpenCode panel appeared and immediately went blank. We instead move the
     * terminal's {@link Content} itself into the OpenCode tool window, which keeps
     * the widget (and its wrapper panel) owned by a single ContentManager.
     */
    public synchronized JPanel ensureTerminal(ToolWindow toolWindow) {
        ToolWindow tw = toolWindow != null ? toolWindow : getToolWindow();
        if (terminalWidget != null && terminalPanel != null) {
            attachPanel(tw, terminalPanel);
            return terminalPanel;
        }
        try {
            // The scrollback cap is read once, when the terminal session's buffer
            // is created, so it must be applied before the widget is created.
            applyTerminalScrollback();
            String wd = project.getBasePath();
            // requestFocus=false: do NOT activate the global Terminal tool window.
            ShellTerminalWidget widget = TerminalView.getInstance(project)
                    .createLocalShellWidget(wd, "OpenCode", false, true);
            OpenCodeTerminalLinkFilter.install(project, widget);
            installEscIntercept(widget);

            JPanel panel = null;
            Content moved = moveTerminalContentInto(tw, widget);
            if (moved != null) {
                java.awt.Component comp = moved.getComponent();
                if (comp instanceof JPanel) {
                    panel = (JPanel) comp;
                } else if (comp != null) {
                    panel = new JPanel(new BorderLayout());
                    panel.add(comp, BorderLayout.CENTER);
                }
            }
            if (panel == null) {
                // Older platform: createLocalShellWidget returns a detached widget.
                panel = new JPanel(new BorderLayout());
                panel.add(widget.getComponent(), BorderLayout.CENTER);
                attachPanel(tw, panel);
            }
            installFileDrop(panel, widget);
            terminalWidget = widget;
            terminalPanel = panel;
            // Defer the command so the widget is attached to a visible component first.
            ApplicationManager.getApplication().invokeLater(this::startTerminalCommandIfNeeded);
            LOG.info("OpenCode terminal panel created");
        } catch (Throwable t) {
            LOG.warn("OpenCode terminal unavailable", t);
            notify("OpenCode", "Could not open the terminal panel: " + t.getMessage(),
                    NotificationType.ERROR);
            return null;
        }
        return terminalPanel;
    }

    /**
     * Move the {@link Content} that hosts {@code widget} from the global Terminal
     * tool window into {@code target}. Returns the moved content, or {@code null}
     * when the widget is not hosted in a Content (older platform API).
     */
    private Content moveTerminalContentInto(ToolWindow target, ShellTerminalWidget widget) {
        if (target == null) return null;
        try {
            ToolWindow terminalTw = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
            if (terminalTw == null) return null;
            java.awt.Component widgetComponent = widget.getComponent();
            if (widgetComponent == null) return null;
            ContentManager terminalCm = terminalTw.getContentManager();
            for (Content c : terminalCm.getContents()) {
                java.awt.Component host = c.getComponent();
                if (host != null && javax.swing.SwingUtilities.isDescendingFrom(widgetComponent, host)) {
                    ContentManager targetCm = target.getContentManager();
                    for (Content old : targetCm.getContents()) targetCm.removeContent(old, true);
                    // Detach without disposing the widget, then attach to our tool window.
                    terminalCm.removeContent(c, false);
                    targetCm.addContent(c);
                    targetCm.setSelectedContent(c, false);
                    LOG.info("Moved OpenCode terminal content into the OpenCode tool window");
                    return c;
                }
            }
        } catch (Throwable t) {
            LOG.warn("Could not relocate terminal content", t);
        }
        return null;
    }

    /** Make sure the given panel is the content of the tool window (idempotent). */
    private void attachPanel(ToolWindow tw, JPanel panel) {
        try {
            if (tw == null) tw = getToolWindow();
            if (tw == null) return;
            ContentManager cm = tw.getContentManager();
            for (Content c : cm.getContents()) {
                if (c.getComponent() == panel) return; // already attached
            }
            for (Content c : cm.getContents()) cm.removeContent(c, true);
            cm.addContent(ContentFactory.getInstance().createContent(panel, "", false));
        } catch (Exception e) {
            LOG.warn("Failed to attach terminal content", e);
        }
    }

    private void ensureTerminalOnEdt() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ensureTerminal();
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) ensureTerminal();
            });
        }
    }

    /**
     * Accept files dropped onto the OpenCode panel and paste them as
     * {@code @path} references into the TUI input. The handler is installed on
     * both the wrapping panel and the terminal component so a drop anywhere in
     * the panel works.
     */
    private void installFileDrop(JPanel panel, ShellTerminalWidget widget) {
        javax.swing.TransferHandler handler = new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    java.util.List<java.io.File> files = (java.util.List<java.io.File>)
                            support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) return false;
                    String base = project.getBasePath();
                    StringBuilder sb = new StringBuilder();
                    for (java.io.File f : files) {
                        String path = f.getAbsolutePath().replace('\\', '/');
                        String rel = path;
                        if (base != null) {
                            String bp = base.replace('\\', '/');
                            if (path.startsWith(bp + "/")) rel = path.substring(bp.length() + 1);
                        }
                        sb.append('@').append(rel.contains(" ") ? "\"" + rel + "\"" : rel).append(' ');
                    }
                    if (sb.length() > 0) {
                        String text = sb.toString().trim();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                widget.getTerminalStarter().sendString(text, false);
                            } catch (Throwable t) {
                                LOG.debug("file drop paste failed", t);
                            }
                        });
                    }
                    return true;
                } catch (Exception ex) {
                    LOG.debug("file drop import failed", ex);
                    return false;
                }
            }
        };
        panel.setTransferHandler(handler);
        try {
            widget.getComponent().setTransferHandler(handler);
        } catch (Throwable ignored) {
        }
    }

    /** Copy the whole terminal buffer to the clipboard as a Markdown code block. */
    public void copyOutputAsMarkdown() {
        ShellTerminalWidget w = terminalWidget;
        if (w == null) return;
        try {
            String text = w.getText();
            if (text == null || text.isBlank()) return;
            String md = "```\n" + text.stripTrailing() + "\n```";
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(new java.awt.datatransfer.StringSelection(md));
        } catch (Throwable t) {
            LOG.debug("copy output failed", t);
        }
    }

    /**
     * Make ESC in the OpenCode panel interrupt the running turn instead of letting
     * IntelliJ move focus to the editor.
     *
     * <p>The terminal panel installs an internal escape listener
     * ({@code com.intellij.terminal.TerminalEscapeKeyListener}) that consumes ESC and
     * calls {@code ToolWindowManager.activateEditorComponent()} unless the terminal is
     * using the alternate screen buffer. The OpenCode TUI renders inline, so that
     * listener always stole ESC and jumped to the editor. We register a "pre key event"
     * handler, which runs <em>before</em> that listener: consuming the event there stops
     * IntelliJ from hijacking ESC, and we forward the raw ESC byte to the TUI so it runs
     * its own {@code session.interrupt} binding. This does not depend on the plugin's
     * (eventually consistent) session tracking.
     */
    private void installEscIntercept(ShellTerminalWidget widget) {
        try {
            JBTerminalPanel panel = widget.getTerminalPanel();
            panel.addPreKeyEventHandler(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ESCAPE) return;
                // Stop IntelliJ's escape -> editor listener: it ignores consumed events.
                e.consume();
                // Forward the raw ESC byte so the TUI interrupts the running turn.
                try {
                    TerminalStarter starter = widget.getTerminalStarter();
                    if (starter != null) starter.sendBytes(new byte[]{0x1b}, false);
                } catch (Throwable t) {
                    LOG.debug("Could not forward ESC to the OpenCode TUI", t);
                }
            });
        } catch (Throwable t) {
            LOG.warn("Could not install ESC intercept on the OpenCode terminal", t);
        }
    }

    /** Launch {@code opencode} in the widget exactly once. */
    private void startTerminalCommandIfNeeded() {
        ShellTerminalWidget widget = terminalWidget;
        if (widget == null) return;
        if (!terminalCommandStarted.compareAndSet(false, true)) return;
        try {
            widget.executeCommand(buildOpenCodeCommand());
            terminalStartedAt = System.currentTimeMillis();
        } catch (Throwable t) {
            LOG.warn("Failed to start opencode in terminal", t);
        }
    }

    /**
     * Command that launches the OpenCode v2 TUI. The v2 CLI connects to the
     * background service by default (no server spawning), which eliminates the
     * old "server dead" problem. OpenCode v2 keeps sessions per working
     * directory, so launching the TUI in the project directory automatically
     * resumes that project's session.
     *
     * <p>OpenCode v2 also has a <em>global</em> TUI client config
     * ({@code ~/.config/opencode/cli.json}) and a tab strip whose state is
     * shared across every client on the machine. Because this plugin runs the
     * TUI in a terminal, opening it in PyCharm and IntelliJ at the same time
     * made session tabs "leak" between the two IDEs (both read/write the same
     * global tab state). To keep tabs unique per project we launch the TUI
     * through a small wrapper that sets {@code OPENCODE_CLI_CONFIG_CONTENT} so
     * {@code tabs.scope} defaults to {@code cwd} (tabs kept per working
     * directory) instead of shared globally.
     *
     * <p>{@code OPENCODE_CLI_CONFIG_CONTENT} is used because it injects
     * <em>only</em> CLI/UI settings (merged over {@code cli.json}) and stays
     * authoritative while set — it does not relocate the config directory, so
     * the user's {@code opencode.json}, agents, commands, plugins and
     * credentials keep working.
     *
     * <p>A wrapper script is used instead of prefixing env assignments onto the
     * command because the IDE's terminal may be PowerShell, cmd.exe, bash or
     * zsh, each with different env syntax. Running a script file works on all
     * of them.
     */
    private String buildOpenCodeCommand() {
        String cmd = getOpenCodeBinary();

        String scope = readSetting(OpenCodeSettingsConfigurable.TAB_SCOPE_KEY, "cwd");
        if (!"global".equals(scope)) scope = "cwd";

        try {
            File script = writeLauncherScript(cmd, scope);
            if (script != null) return quoteArg(script.getAbsolutePath());
        } catch (Exception e) {
            LOG.warn("could not write OpenCode launcher script; falling back to plain launch", e);
        }
        // Fallback: launch the plain binary if the script could not be written.
        return quoteArg(cmd);
    }

    private String quoteArg(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    /**
     * Write a platform-appropriate launcher script that overrides the OpenCode
     * v2 TUI tab scope and then execs the OpenCode binary.
     *
     * <p>We deliberately use <b>only</b> {@code OPENCODE_CLI_CONFIG_CONTENT}
     * (which injects just CLI/UI settings and stays authoritative while set)
     * and never point {@code OPENCODE_CONFIG_DIR}/{@code XDG_CONFIG_HOME}
     * elsewhere — that would relocate the whole config directory and hide the
     * user's {@code opencode.json}, agents, commands, plugins and credentials.
     */
    private File writeLauncherScript(String binary, String scope) throws Exception {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        File dir = new File(home, ".config/opencode");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String json = "{\"tabs\":{\"scope\":\"" + scope + "\"}}";

        if (isWindows()) {
            File script = new File(dir, "opencode-launch.cmd");
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\r\n");
            sb.append("set \"OPENCODE_CLI_CONFIG_CONTENT=").append(json).append("\"\r\n");
            sb.append(quoteArg(binary)).append("\r\n");
            Files.write(script.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return script;
        }
        File script = new File(dir, "opencode-launch.sh");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("OPENCODE_CLI_CONFIG_CONTENT=").append(shellQuote(json)).append("\n");
        sb.append("export OPENCODE_CLI_CONFIG_CONTENT\n");
        sb.append("exec ").append(shellQuote(binary)).append("\n");
        Files.write(script.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        script.setExecutable(true, false);
        return script;
    }

    /** POSIX single-quote a value (safe for the JSON payload). */
    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String readSetting(String key, String def) {
        try {
            String v = com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(key, def);
            return v == null || v.isBlank() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private int readIntSetting(String key, int def) {
        try {
            return com.intellij.ide.util.PropertiesComponent.getInstance(project).getInt(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Apply the configured terminal scrollback size to the IDE's advanced setting
     * {@code terminal.buffer.max.lines.count}.
     *
     * <p>The OpenCode TUI renders inside a standard IDE terminal, whose history is
     * capped by that key (JetBrains' default is 5000 lines). The plugin used to
     * trim the buffer itself to save RAM; that is gone, and by default the cap is
     * removed entirely so nothing is dropped. The history storage only evicts once
     * the line count exceeds the cap and never pre-allocates, so an "unlimited"
     * value costs nothing until the lines are really produced.
     *
     * <p>The value is read at terminal-creation time, hence this is called right
     * before the widget is built and the terminal is restarted after a change.
     */
    private void applyTerminalScrollback() {
        try {
            int lines = readIntSetting(OpenCodeSettingsConfigurable.TERMINAL_BUFFER_KEY,
                    OpenCodeSettingsConfigurable.DEFAULT_TERMINAL_BUFFER_LINES);
            // 0 = no line limit: use the largest cap JetBrains accepts. The buffer
            // would be exhausted long before reaching it, i.e. effectively unbounded.
            if (lines <= 0) lines = Integer.MAX_VALUE;
            AdvancedSettings.getInstance().setInt(TERMINAL_MAX_LINES_KEY, lines);
        } catch (Throwable t) {
            LOG.debug("could not apply terminal scrollback setting", t);
        }
    }

    /** Forget the cached binary path (called after the setting changes). */
    public void resetCachedBinary() {
        cachedBinary = null;
    }

    /**
     * Restart the TUI terminal so launch-time settings (tab scope / per-project
     * isolation) take effect. Called after the settings page is applied.
     */
    public void restartTerminal() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget old = terminalWidget;
            terminalWidget = null;
            terminalPanel = null;
            terminalCommandStarted.set(false);
            terminalStartedAt = 0;
            if (old != null) {
                try {
                    old.dispose();
                } catch (Exception ignored) {
                }
            }
            ensureTerminal();
        });
    }

    /** Resolve the opencode CLI binary (cache result). */
    private String getOpenCodeBinary() {
        if (cachedBinary != null) return cachedBinary;
        // 1) Explicit path from settings, if any.
        try {
            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance(project);
            String configured = pc.getValue("opencode.binaryPath", "");
            if (configured != null && !configured.isBlank()) {
                File f = new File(configured.trim());
                if (f.exists() && f.canExecute()) {
                    cachedBinary = f.getAbsolutePath();
                    return cachedBinary;
                }
                LOG.warn("Configured OpenCode binary not executable, falling back: " + configured);
            }
        } catch (Exception ignored) {
            // Settings unavailable (e.g. during early startup) -> auto-detect.
        }
        String home = System.getProperty("user.home");
        String localAppData = System.getenv("LOCALAPPDATA");
        File[] candidates = isWindows()
                ? new File[]{
                        new File(home, ".opencode/bin/opencode.exe"),
                        new File("C:\\Program Files\\opencode\\opencode.exe"),
                        localAppData == null ? null : new File(localAppData, "opencode\\opencode.exe"),
                }
                : new File[]{
                        new File(home, ".opencode/bin/opencode"),
                        new File("/opt/homebrew/bin/opencode"),
                        new File("/usr/local/bin/opencode"),
                        new File("/usr/bin/opencode"),
                };
        for (File c : candidates) {
            if (c != null && c.exists() && c.canExecute()) {
                cachedBinary = c.getAbsolutePath();
                return cachedBinary;
            }
        }
        cachedBinary = "opencode";
        return cachedBinary;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private ToolWindow getToolWindow() {
        try {
            return ToolWindowManager.getInstance(project).getToolWindow("OpenCode");
        } catch (Exception e) {
            return null;
        }
    }

    /** Focus the TUI and paste a reference (e.g. "@path#L1-5 ") as context. */
    public void focusOrCreateTerminalAndPaste(String text) {
        focusOrCreateTerminalAndType(text, false);
    }

    /**
     * Focus the TUI, type the given text and press Enter so it runs immediately.
     * Used by the code actions (Explain / Fix / Refactor ...) where the user
     * expects the request to start without an extra keypress.
     */
    public void focusOrCreateTerminalAndRun(String text) {
        focusOrCreateTerminalAndType(text, true);
    }

    private void focusOrCreateTerminalAndType(String text, boolean submit) {
        if (text == null || text.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        // If the TUI was just launched it needs a few seconds to boot before it
        // can accept input; otherwise the typed text would be lost.
        long sinceStart = System.currentTimeMillis() - terminalStartedAt;
        long bootWait = terminalStartedAt == 0 ? 0 : Math.max(0, 3500 - sinceStart);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    // Focus the terminal so the typed text is visible.
                    final ShellTerminalWidget w = widget;
                    final Project p = project;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            ToolWindow tw = ToolWindowManager.getInstance(p).getToolWindow("OpenCode");
                            if (tw != null) tw.activate(null, false);
                        } catch (Exception ignored) {
                        }
                    });
                    if (bootWait > 0) {
                        // Wait for the TUI to boot, then type (and optionally submit).
                        appScheduler().schedule(
                                () -> ApplicationManager.getApplication().invokeLater(
                                        () -> typeIntoTerminal(w, text, submit)),
                                bootWait, TimeUnit.MILLISECONDS);
                    } else {
                        typeIntoTerminal(w, text, submit);
                    }
                } catch (Exception e) {
                    LOG.debug("paste via terminal failed", e);
                }
            });
        });
    }

    /** Send the text to the TUI and, when requested, press Enter shortly after. */
    private void typeIntoTerminal(ShellTerminalWidget w, String text, boolean submit) {
        if (project.isDisposed()) return;
        try {
            w.getTerminalStarter().sendString(text, false);
            if (submit) {
                // Let the TUI render the typed text (and close any @mention
                // completion popup) before submitting, so Enter starts the
                // request instead of just picking a completion.
                appScheduler().schedule(
                        () -> ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                if (!project.isDisposed()) {
                                    w.getTerminalStarter().sendBytes(new byte[]{'\r'}, false);
                                }
                            } catch (Throwable ignored) {
                            }
                        }), 450, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            LOG.debug("type into terminal failed", t);
        }
    }

    /** Shared scheduled executor for short UI-related delays. */
    private static java.util.concurrent.ScheduledExecutorService appScheduler() {
        return AppExecutorUtil.getAppScheduledExecutorService();
    }

    /** Type a line into the OpenCode TUI and press Enter. */
    public void sendCommandToTerminal(String command) {
        if (command == null || command.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            try {
                widget.getTerminalStarter().sendString(command + "\r", false);
            } catch (Throwable t) {
                LOG.debug("sendCommandToTerminal failed", t);
            }
        });
    }

    /**
     * Ask the OpenCode TUI to start a fresh session ({@code /new}) and, shortly
     * after, re-discover the project's session so the plugin's diff/turn tracking
     * follows the new one.
     */
    public void startNewSession() {
        sendCommandToTerminal("/new");
        poller.schedule(() -> {
            try {
                discoverSession();
                notifyChanged();
            } catch (Exception e) {
                LOG.debug("session re-discovery after /new failed", e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /** Called when the terminal panel was disposed (tool window closed for good / project close). */
    public void onTerminalDisposed() {
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        terminalStartedAt = 0;
        LOG.info("OpenCode terminal disposed");
    }

    // ------------------------------------------------------------------
    // Turn tracking + diff
    // ------------------------------------------------------------------

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return;
        poller.scheduleWithFixedDelay(this::pollOnBackground, 1, 2, TimeUnit.SECONDS);
    }

    private void pollOnBackground() {
        if (disposed.get() || api == null || sessionId == null) return;
        try {
            Map<String, String> active = api.activeSessions();
            consecutiveFailures = 0;
            boolean running = active.containsKey(sessionId);
            boolean was = turnActive;
            turnActive = running;
            if (running != was) {
                if (running) {
                    lastUserMsgId = null;
                } else if (was) {
                    // Turn just finished -> fetch diffs and notify the user.
                    refreshDiffs(true);
                }
                notifyChanged();
            }
        } catch (Exception e) {
            // Server unreachable: count consecutive failures and reconnect silently
            // after a few, so a long running task / server restart does not leave
            // the plugin stuck in "Server dead".
            consecutiveFailures++;
            if (consecutiveFailures == RECONNECT_AFTER_FAILURES) {
                LOG.warn("[OpenCode] " + RECONNECT_AFTER_FAILURES + " consecutive poll failures - auto-reconnecting");
                reconnectSilently();
            }
        }
    }

    /**
     * Re-discover the background service and re-connect without showing the
     * manual connection dialog. Used by auto-reconnect and by {@link #reconnect()}.
     */
    public void reconnectSilently() {
        if (connecting || disposed.get()) return;
        teardown();
        connected = false;
        api = null;
        conn = null;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ServerConnection c = ServerConnection.discover();
                if (c != null && ServerConnection.isReachable(c, 1500)) {
                    applyConnection(c);
                }
            } finally {
                connecting = false;
                notifyChanged();
            }
        });
    }

    private void startEventStream() {
        if (conn == null) return;
        events = new OpenCodeEventStream(conn.url, "opencode", conn.password,
                this::onEvent, this::onStreamState);
        events.start();
    }

    private void onStreamState(boolean up) {
        // SSE is volatile by design; don't flip the whole connection state on it.
    }

    private void onEvent(String type, JsonObject data) {
        if ("server.connected".equals(type)) notifyChanged();
    }

    /** Send a prompt to the current session. */
    public void sendPrompt(String text, List<JsonObject> files) {
        if (api == null || sessionId == null || text == null || text.isBlank()) {
            notify("OpenCode", "Not connected to an OpenCode session.", NotificationType.WARNING);
            return;
        }
        final String sid = sessionId;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            String msgId = api.prompt(sid, text, files);
            if (msgId != null) {
                lastUserMsgId = msgId;
                turnActive = true;
                notifyChanged();
            } else {
                notify("OpenCode", "Prompt was not accepted by the server.", NotificationType.ERROR);
            }
        });
    }

    public void interrupt() {
        if (api != null && sessionId != null) {
            final String sid = sessionId;
            AppExecutorUtil.getAppExecutorService().execute(() -> api.interrupt(sid));
        }
    }

    /** Fetch /diff for the last completed turn and open the diff viewer. */
    public void refreshDiffs() {
        refreshDiffs(false);
    }

    /**
     * Fetch /diff for the last completed turn and open the diff viewer.
     *
     * @param notifyOnComplete when true (turn finished on its own) also show a
     *                         balloon notification summarising the result, so the
     *                         user notices the agent is done even when the panel
     *                         is not focused.
     */
    public void refreshDiffs(boolean notifyOnComplete) {
        if (api == null || sessionId == null) return;
        String sid = sessionId;
        String from = lastUserMsgId;
        if (from == null || from.isBlank()) {
            // Fall back to the newest user message.
            List<JsonObject> msgs = api.listMessages(sid, "desc", 20);
            for (JsonObject m : msgs) {
                String type = m.has("type") && !m.get("type").isJsonNull() ? m.get("type").getAsString() : "";
                if ("user".equals(type)) {
                    from = m.has("id") && !m.get("id").isJsonNull() ? m.get("id").getAsString() : null;
                    break;
                }
            }
        }
        final String anchor = from;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                List<JsonObject> list = api.sessionDiff(sid, anchor, null);
                List<FileDiff> diffs = new ArrayList<>();
                for (JsonObject o : list) {
                    try {
                        diffs.add(FileDiff.fromJson(o));
                    } catch (Exception e) {
                        LOG.debug("parse diff failed", e);
                    }
                }
                // Refresh the VFS first: files the AI just created on disk may not be
                // known to IntelliJ yet, so reading their content before the refresh
                // would yield empty "after" content for brand-new files.
                forceVfsRefresh(diffs);
                List<ResolvedDiff> resolved = new ArrayList<>();
                SessionManager sm = ServiceManager.getService(project, SessionManager.class);
                for (FileDiff fd : diffs) {
                    try {
                        String after = readCurrentContent(fd.file);
                        if (after.isEmpty() && fd.isNewFile()) {
                            // Safety net: reconstruct the content from the patch when the
                            // VFS still cannot see the brand-new file.
                            String fromPatch = UnifiedPatch.extractAddedContent(fd.patch);
                            if (fromPatch != null) after = fromPatch;
                        }
                        String before = resolveBefore(fd, after, sm);
                        resolved.add(new ResolvedDiff(fd, before, after));
                    } catch (Exception e) {
                        LOG.debug("resolve diff failed", e);
                    }
                }
                lastDiffs.clear();
                lastDiffs.addAll(resolved);
                notifyChanged();
                if (!resolved.isEmpty() && !project.isDisposed()) {
                    new DiffViewerService(project).showDiffs(resolved);
                }
                if (notifyOnComplete) notifyTurnComplete(resolved);
            } catch (Exception e) {
                LOG.warn("diff refresh failed", e);
            }
        });
    }

    /** Balloon shown when a turn finishes, with a shortcut to the diff viewer. */
    private void notifyTurnComplete(List<ResolvedDiff> resolved) {
        if (project.isDisposed()) return;
        final List<ResolvedDiff> snapshot = new ArrayList<>(resolved);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                String title = "OpenCode finished";
                String content = snapshot.isEmpty()
                        ? "The agent finished with no file changes."
                        : "The agent changed " + snapshot.size()
                                + (snapshot.size() == 1 ? " file." : " files.");
                Notification n = NotificationGroupManager.getInstance()
                        .getNotificationGroup(OpenCodePlugin.NOTIFICATION_GROUP)
                        .createNotification(title, content, NotificationType.INFORMATION);
                if (!snapshot.isEmpty()) {
                    n.addAction(NotificationAction.createSimpleExpiring(
                            "Review Diffs", () -> new DiffViewerService(project).showDiffs(snapshot)));
                }
                n.notify(project);
            } catch (Exception e) {
                LOG.debug("completion notification failed", e);
            }
        });
    }

    private String resolveBefore(FileDiff fd, String after, SessionManager sm) {
        if (fd.isNewFile()) return "";
        if (sm != null) {
            String snap = sm.beforeContent(fd.file);
            if (snap != null) return snap;
        }
        String reverse = UnifiedPatch.reverseApply(fd.patch, after);
        return reverse != null ? reverse : "";
    }

    /**
     * Makes IntelliJ's VFS aware of files OpenCode just created, modified or
     * deleted on disk. Without this, brand-new files are invisible to the VFS
     * and {@link #readCurrentContent} returns empty content for them.
     */
    private void forceVfsRefresh(List<FileDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) return;
        try {
            String base = project.getBasePath();
            if (base == null) return;
            List<File> files = new ArrayList<>();
            for (FileDiff d : diffs) {
                File ioFile = new File(base, d.file.replace('/', File.separatorChar));
                // If the file is deleted, refresh the parent directory so the
                // deletion is picked up.
                if (!ioFile.exists() && ioFile.getParentFile() != null) ioFile = ioFile.getParentFile();
                if (ioFile.exists()) files.add(ioFile);
            }
            if (!files.isEmpty()) {
                LOG.info("OpenCode: forcing VFS refresh for " + files.size() + " paths");
                LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null);
            }
        } catch (Exception e) {
            LOG.debug("VFS refresh skipped: " + e.getMessage());
        }
    }

    private String readCurrentContent(String rel) {
        try {
            String base = project.getBasePath();
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(
                    base == null ? rel : base.replace('\\', '/') + "/" + rel.replace('\\', '/'));
            if (file == null || !file.isValid() || file.isDirectory()) return "";
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public boolean isConnected() {
        return connected;
    }

    public boolean isBusy() {
        return turnActive;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getStatusText() {
        if (!connected) return "Not connected";
        String base = conn == null ? "" : " " + conn.display();
        return (turnActive ? "Busy (session running)" : "Connected") + base;
    }

    public List<ResolvedDiff> getLastDiffs() {
        return lastDiffs;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void notifyChanged() {
        // UI listeners (tool window) must be notified on the EDT.
        List<Runnable> snapshot = new ArrayList<>(listeners);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            for (Runnable r : snapshot) r.run();
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                for (Runnable r : snapshot) r.run();
            });
        }
    }

    private void notify(String title, String content, NotificationType type) {
        try {
            ApplicationManager.getApplication().invokeLater(() ->
                    NotificationGroupManager.getInstance()
                            .getNotificationGroup(OpenCodePlugin.NOTIFICATION_GROUP)
                            .createNotification(title, content, type)
                            .notify(project));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void dispose() {
        disposed.set(true);
        teardown();
        poller.shutdownNow();
        listeners.clear();
        // Xóa bộ nhớ diffs
        lastDiffs.clear();
        // Dispose the terminal widget if still alive.
        ShellTerminalWidget w = terminalWidget;
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        terminalStartedAt = 0;
        if (w != null) {
            try {
                w.dispose();
            } catch (Exception ignored) {
            }
        }
    }
}