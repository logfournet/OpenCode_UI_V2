package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * OpenCode docked panel.
 *
 * The OpenCode TUI is hosted in this tool window's content (a real terminal
 * widget from the Terminal plugin) instead of an editor tab. That gives the
 * familiar "file tree | code | agent" layout: the code editor stays visible
 * next to the OpenCode panel, so you can read the code while the agent works.
 *
 * The panel itself is created and attached by {@link OpenCodeService#ensureTerminal()};
 * this factory only makes sure it exists when the tool window is first shown.
 */
public final class OpenCodeToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ServiceManager.getService(project, OpenCodeService.class).ensureTerminal();
    }
}
