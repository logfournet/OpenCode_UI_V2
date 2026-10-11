package ai.opencode.ide.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import org.jetbrains.annotations.NotNull;

/**
 * "OpenCode" submenu shown in the editor context menu: canned AI code actions
 * (Explain / Fix / Refactor / Add tests / Optimize / Document) plus a free-form
 * prompt entry. Each action pastes a prompt + the current selection reference
 * into the OpenCode TUI input.
 */
public final class OpenCodeActionsGroup extends DefaultActionGroup {

    public OpenCodeActionsGroup() {
        getTemplatePresentation().setText("OpenCode");
        getTemplatePresentation().setIcon(
                com.intellij.openapi.util.IconLoader.getIcon("/icons/opencode.svg", OpenCodeActionsGroup.class));
        setPopup(true);

        add(new OpenCodeAskAction("Explain", "Explain"));
        add(new OpenCodeAskAction("Fix", "Fix the problems in"));
        add(new OpenCodeAskAction("Refactor", "Refactor"));
        add(new OpenCodeAskAction("Add Tests", "Write tests for"));
        add(new OpenCodeAskAction("Optimize", "Optimize"));
        add(new OpenCodeAskAction("Document", "Add documentation for"));
        add(Separator.getInstance());
        add(new OpenCodeAskWithPromptAction());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
