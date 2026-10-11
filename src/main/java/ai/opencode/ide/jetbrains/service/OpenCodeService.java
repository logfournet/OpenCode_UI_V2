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
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditor;
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditorProvider;
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter;
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile;
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
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

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

    // Terminal UI (mirrors the 1.x "OpenCode tab in the editor area" UX).
    private volatile OpenCodeTerminalVirtualFile terminalVirtualFile;
    private volatile OpenCodeTerminalFileEditor terminalEditor;
    private volatile ShellTerminalWidget terminalWidget;
    private volatile String cachedBinary;

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
    // Terminal UI (OpenCode TUI in an editor-area tab, same UX as 1.x)
    // ------------------------------------------------------------------

    /**
     * Open (or focus) the OpenCode TUI terminal tab. This is what the sidebar
     * icon and the QuickLaunch action call.
     */
    public void focusOrCreateTerminal() {
        if (project.isDisposed()) return;
        if (hasTerminalUI()) {
            focusTerminalUI();
            return;
        }
        if (isConnected() || connecting) {
            // Connected to the v2 background service: just open the TUI, which
            // connects to that same service (no --hostname/--port spawning).
            createTerminalUI();
            return;
        }
        // First connect (silently re-discover service.json), then open the TUI.
        connect();
        // Opening the terminal does not depend on the API; the TUI itself talks
        // to the service. Open it right away.
        createTerminalUI();
    }

    /** True when the OpenCode terminal tab is currently open in an editor. */
    public boolean hasTerminalUI() {
        OpenCodeTerminalVirtualFile f = terminalVirtualFile;
        if (f == null) return false;
        try {
            return FileEditorManager.getInstance(project).isFileOpen(f);
        } catch (Exception e) {
            return false;
        }
    }

    private void focusTerminalUI() {
        OpenCodeTerminalVirtualFile f = terminalVirtualFile;
        if (f != null) {
            try {
                FileEditorManager.getInstance(project).openFile(f, true);
            } catch (Exception ignored) {
            }
        }
    }

    /** Create the OpenCode terminal tab and run the TUI in it. */
    private void createTerminalUI() {
        try {
            String tabName = "OpenCode";
            String wd = project.getBasePath();
            ShellTerminalWidget widget = TerminalView.getInstance(project).createLocalShellWidget(wd, tabName);
            OpenCodeTerminalLinkFilter.install(project, widget);
            OpenCodeTerminalVirtualFile f = new OpenCodeTerminalVirtualFile(tabName);
            terminalVirtualFile = f;
            terminalWidget = widget;
            OpenCodeTerminalFileEditorProvider.registerWidget(f, widget);

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    FileEditor[] editors = FileEditorManager.getInstance(project).openFile(f, true);
                    for (FileEditor ed : editors) {
                        if (ed instanceof OpenCodeTerminalFileEditor) {
                            terminalEditor = (OpenCodeTerminalFileEditor) ed;
                            break;
                        }
                    }
                    widget.executeCommand(buildOpenCodeCommand());
                    pinTerminalTab(f);
                } catch (Exception e) {
                    LOG.warn("Failed to create OpenCode terminal editor", e);
                }
            });
        } catch (Throwable t) {
            LOG.warn("OpenCode terminal unavailable", t);
            notify("OpenCode", "Could not open the terminal tab: " + t.getMessage(),
                    NotificationType.ERROR);
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

    private void pinTerminalTab(VirtualFile f) {
        try {
            FileEditorManagerEx editorManager = FileEditorManagerEx.getInstanceEx(project);
            if (editorManager.getCurrentWindow() != null) {
                editorManager.getCurrentWindow().setFilePinned(f, true);
            }
        } catch (Exception ignored) {
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
                    com.jediterm.terminal.ui.JediTermWidget jt = widget;
                    com.jediterm.terminal.TerminalStarter starter = jt.getTerminalStarter();
                    starter.sendString(text, false);
                } catch (Exception e) {
                    LOG.debug("paste via terminal failed", e);
                }
            });
        });
    }

    /** Called when the terminal tab was truly closed (after the disposal grace period). */
    public void onTerminalDisposed() {
        terminalVirtualFile = null;
        terminalEditor = null;
        terminalWidget = null;
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
                List<ResolvedDiff> resolved = new ArrayList<>();
                SessionManager sm = ServiceManager.getService(project, SessionManager.class);
                for (JsonObject o : list) {
                    try {
                        FileDiff fd = FileDiff.fromJson(o);
                        String after = readCurrentContent(fd.file);
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
        OpenCodeTerminalFileEditorProvider.clearAll();
        terminalVirtualFile = null;
        terminalEditor = null;
        terminalWidget = null;
    }
}