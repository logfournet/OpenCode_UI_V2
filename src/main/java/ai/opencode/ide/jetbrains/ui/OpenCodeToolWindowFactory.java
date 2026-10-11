package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;

/**
 * OpenCode sidebar icon.
 *
 * Clicking the icon triggers {@link OpenCodeService#focusOrCreateTerminal()} —
 * the OpenCode TUI is opened in an editor-area tab exactly like the 1.x plugin.
 * No Swing panel is displayed.
 */
public final class OpenCodeToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Add dummy content to avoid the "Empty Tool Window" warning.
        toolWindow.getContentManager().addContent(
                ContentFactory.getInstance().createContent(new JPanel(), "", false));

        // Hide it quietly if it was restored from a saved IDE state.
        if (toolWindow.isVisible()) toolWindow.hide();

        // When the user clicks the sidebar icon (tool window shown) -> open the TUI.
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(
                ToolWindowManagerListener.TOPIC,
                new ToolWindowManagerListener() {
                    @Override
                    public void toolWindowShown(@NotNull ToolWindow tw) {
                        if ("OpenCode".equals(tw.getId())) {
                            ServiceManager.getService(project, OpenCodeService.class)
                                    .focusOrCreateTerminal();
                            tw.hide();
                        }
                    }
                });
    }
}