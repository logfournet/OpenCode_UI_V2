package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import ai.opencode.ide.jetbrains.util.OpenCodeReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * A code action that pastes a canned prompt (plus the current selection/file
 * reference) into the OpenCode TUI input. The user reviews it and presses Enter.
 */
public class OpenCodeAskAction extends AnAction {

    private final String prompt;

    public OpenCodeAskAction(@NotNull String text, @NotNull String prompt) {
        super(text);
        this.prompt = prompt;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String ref = OpenCodeReference.from(project, e);
        String text = ref.isEmpty() ? prompt : prompt + " " + ref;
        ServiceManager.getService(project, OpenCodeService.class).focusOrCreateTerminalAndPaste(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
