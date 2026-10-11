package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.diff.DiffViewerService;
import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Re-opens (or fetches) the diff of the last OpenCode turn.
 */
public final class OpenDiffsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        OpenCodeService svc = ServiceManager.getService(p, OpenCodeService.class);
        if (svc.getLastDiffs().isEmpty()) {
            svc.refreshDiffs();
        } else {
            new DiffViewerService(p).showDiffs(svc.getLastDiffs());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
