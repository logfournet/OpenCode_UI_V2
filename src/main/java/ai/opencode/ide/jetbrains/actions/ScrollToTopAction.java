package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Scrolls the OpenCode output to the first message.
 */
public final class ScrollToTopAction extends AnAction {

    public ScrollToTopAction() {
        super("Scroll to Top", "Scroll the OpenCode output to the first message", AllIcons.Actions.MoveUp);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        OpenCodeService svc = p.getService(OpenCodeService.class);
        svc.scrollToTop();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
