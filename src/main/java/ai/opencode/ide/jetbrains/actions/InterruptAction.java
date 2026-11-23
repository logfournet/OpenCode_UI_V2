package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Interrupts the currently running OpenCode generation.
 */
public final class InterruptAction extends AnAction {

    public InterruptAction() {
        super("Stop", "Interrupt the current OpenCode generation", AllIcons.Actions.Suspend);
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
        svc.interrupt();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        boolean busy = p != null && !p.isDisposed() && p.getService(OpenCodeService.class).isBusy();
        e.getPresentation().setEnabled(busy);
    }
}
