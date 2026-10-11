package ai.opencode.ide.jetbrains.terminal;

import ai.opencode.ide.jetbrains.OpenCodePlugin;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

/**
 * Opens the interactive OpenCode TUI inside the IDE's terminal tool window.
 * Uses the bundled terminal plugin (optional dependency; failures degrade to a
 * notification instead of breaking the plugin).
 */
public final class OpenCodeTerminalLauncher {

    private OpenCodeTerminalLauncher() {
    }

    public static void open(Project project) {
        if (project == null || project.isDisposed()) return;
        try {
            Class.forName("org.jetbrains.plugins.terminal.TerminalView");
            String dir = project.getBasePath();
            org.jetbrains.plugins.terminal.TerminalView tv =
                    org.jetbrains.plugins.terminal.TerminalView.getInstance(project);
            org.jetbrains.plugins.terminal.ShellTerminalWidget widget = tv.createLocalShellWidget(dir, "OpenCode");
            widget.executeCommand("opencode");
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
            if (tw != null) tw.activate(null);
        } catch (Throwable t) {
            try {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(OpenCodePlugin.NOTIFICATION_GROUP)
                        .createNotification("OpenCode Terminal",
                                "Could not open the terminal tab: " + t.getMessage(), NotificationType.ERROR)
                        .notify(project);
            } catch (Exception ignored) {
            }
        }
    }
}
