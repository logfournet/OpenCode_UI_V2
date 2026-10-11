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
import ai.opencode.ide.jetbrains.util.ServerConnection;
import com.google.gson.JsonObject;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.awt.BorderLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
        if (terminalWidget != null && terminalPanel != null) return terminalPanel;
        try {
            String wd = project.getBasePath();
            ShellTerminalWidget widget = TerminalView.getInstance(project)
                    .createLocalShellWidget(wd, "OpenCode");
            OpenCodeTerminalLinkFilter.install(project, widget);
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(widget.getComponent(), BorderLayout.CENTER);
            terminalWidget = widget;
            terminalPanel = panel;
            attachTerminalContent(panel);
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

    /** Replace the placeholder tool window content with the live terminal panel. */
    private void attachTerminalContent(JPanel panel) {
        try {
            ToolWindow tw = getToolWindow();
            if (tw == null) return;
            ContentManager cm = tw.getContentManager();
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

    /** Launch {@code opencode} in the widget exactly once. */
    private void startTerminalCommandIfNeeded() {
        ShellTerminalWidget widget = terminalWidget;
        if (widget == null) return;
        if (!terminalCommandStarted.compareAndSet(false, true)) return;
        try {
            widget.executeCommand(buildOpenCodeCommand());
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
     */
    private String buildOpenCodeCommand() {
        String cmd = getOpenCodeBinary();
        return cmd.contains(" ") ? "\"" + cmd + "\"" : cmd;
    }

    /** Resolve the opencode CLI binary (cache result). */
    private String getOpenCodeBinary() {
        if (cachedBinary != null) return cachedBinary;
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
        if (text == null || text.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    // Focus the terminal so the pasted text is visible.
                    final ShellTerminalWidget w = widget;
                    final Project p = project;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            ToolWindow tw = ToolWindowManager.getInstance(p).getToolWindow("OpenCode");
                            if (tw != null) tw.activate(null, false);
                        } catch (Exception ignored) {
                        }
                    });
                    com.jediterm.terminal.ui.JediTermWidget jt = w;
                    com.jediterm.terminal.TerminalStarter starter = jt.getTerminalStarter();
                    starter.sendString(text, false);
                } catch (Exception e) {
                    LOG.debug("paste via terminal failed", e);
                }
            });
        });
    }

    /** Called when the terminal panel was disposed (tool window closed for good / project close). */
    public void onTerminalDisposed() {
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
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
                    // Turn just finished -> fetch diffs.
                    refreshDiffs();
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
            } catch (Exception e) {
                LOG.warn("diff refresh failed", e);
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
        // Dispose the terminal widget if still alive.
        ShellTerminalWidget w = terminalWidget;
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        if (w != null) {
            try {
                w.dispose();
            } catch (Exception ignored) {
            }
        }
    }
}