package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Copies the entire OpenCode terminal buffer to the clipboard as a fenced
 * Markdown code block.
 */
public final class CopyOutputAction extends AnAction {

    public CopyOutputAction() {
        super("Copy Output as Markdown", "Copy the whole OpenCode terminal output as a Markdown code block", AllIcons.Actions.Copy);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        p.getService(OpenCodeService.class).copyOutputAsMarkdown();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabledAndVisible(p != null && !p.isDisposed());
    }
}
