package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Interrupts the currently running OpenCode generation.
 */
public final class InterruptAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        OpenCodeService svc = ServiceManager.getService(p, OpenCodeService.class);
        svc.interrupt();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        boolean busy = p != null && ServiceManager.getService(p, OpenCodeService.class).isBusy();
        e.getPresentation().setEnabled(busy);
    }
}
