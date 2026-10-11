package ai.opencode.ide.jetbrains.terminal;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Provider for OpenCode terminal editors. Mirrors the 1.x provider: manages the
 * terminal widget lifecycle with a "delayed disposal" grace period so that
 * moving a tab (close + immediate reopen) does not kill the running session.
 */
public final class OpenCodeTerminalFileEditorProvider implements FileEditorProvider, DumbAware {

    private static final Logger LOG = Logger.getInstance(OpenCodeTerminalFileEditorProvider.class);

    private static final ConcurrentHashMap<OpenCodeTerminalVirtualFile, ShellTerminalWidget> WIDGETS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<OpenCodeTerminalVirtualFile, ScheduledFuture<?>> DISPOSAL_TASKS =
            new ConcurrentHashMap<>();

    public static void registerWidget(OpenCodeTerminalVirtualFile file, ShellTerminalWidget widget) {
        cancelDisposal(file);
        WIDGETS.put(file, widget);
    }

    public static void unregisterWidget(OpenCodeTerminalVirtualFile file) {
        WIDGETS.remove(file);
        cancelDisposal(file);
    }

    public static boolean hasWidget(OpenCodeTerminalVirtualFile file) {
        return WIDGETS.containsKey(file);
    }

    /** Schedule widget disposal with a grace period (handles the tab-move scenario). */
    public static void scheduleDisposal(OpenCodeTerminalVirtualFile file, Project project) {
        cancelDisposal(file);
        if (!WIDGETS.containsKey(file)) return;

        ScheduledFuture<?> task = AppExecutorUtil.getAppScheduledExecutorService().schedule(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    boolean stillOpen = FileEditorManager.getInstance(project).isFileOpen(file);
                    if (stillOpen) {
                        LOG.debug("Aborting disposal for " + file.getName() + ": file still open (move/split)");
                    } else {
                        disposeWidget(file, project);
                    }
                }), 60, TimeUnit.SECONDS);
        DISPOSAL_TASKS.put(file, task);
    }

    public static void cancelDisposal(OpenCodeTerminalVirtualFile file) {
        ScheduledFuture<?> task = DISPOSAL_TASKS.remove(file);
        if (task != null && !task.isDone()) task.cancel(false);
    }

    public static void disposeWidget(OpenCodeTerminalVirtualFile file, Project project) {
        DISPOSAL_TASKS.remove(file);
        ShellTerminalWidget widget = WIDGETS.remove(file);
        if (widget != null) {
            try {
                widget.dispose();
            } catch (Exception e) {
                LOG.warn("Error disposing terminal widget", e);
            }
            if (project != null && !project.isDisposed()) {
                ServiceManager.getService(project, OpenCodeService.class).onTerminalDisposed();
            }
        }
    }

    /** Clear all registered widgets (during plugin unload / project dispose). */
    public static void clearAll() {
        DISPOSAL_TASKS.values().forEach(t -> t.cancel(false));
        DISPOSAL_TASKS.clear();
        for (OpenCodeTerminalVirtualFile f : WIDGETS.keySet()) {
            disposeWidget(f, null);
        }
        WIDGETS.clear();
    }

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof OpenCodeTerminalVirtualFile;
    }

    @NotNull
    @Override
    public FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        OpenCodeTerminalVirtualFile f = (OpenCodeTerminalVirtualFile) file;
        cancelDisposal(f);
        ShellTerminalWidget widget = WIDGETS.get(f);
        if (widget == null) {
            LOG.warn("Terminal widget not found for " + f.getName());
        }
        return new OpenCodeTerminalFileEditor(project, f, widget);
    }

    @NotNull
    @Override
    public String getEditorTypeId() {
        return "opencode-terminal-editor";
    }

    @NotNull
    @Override
    public FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }
}