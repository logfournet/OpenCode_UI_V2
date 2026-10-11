package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import ai.opencode.ide.jetbrains.util.OpenCodeReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Prompts the user for free-form text and pastes it (plus the current selection /
 * file reference) into the OpenCode TUI input.
 */
public final class OpenCodeAskWithPromptAction extends AnAction {

    public OpenCodeAskWithPromptAction() {
        super("Ask OpenCode\u2026");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String prompt = Messages.showInputDialog(project,
                "What should OpenCode do?", "Ask OpenCode", Messages.getQuestionIcon());
        if (prompt == null || prompt.isBlank()) return;
        String ref = OpenCodeReference.from(project, e);
        String text = ref.isEmpty() ? prompt.trim() : prompt.trim() + " " + ref;
        ServiceManager.getService(project, OpenCodeService.class).focusOrCreateTerminalAndPaste(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
