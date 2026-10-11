package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Handles proper disposal of OpenCode terminal widgets: when a tab is closed we
 * wait (grace period) before disposing; if the tab is merely being moved or is
 * still open in another splitter, the disposal is cancelled.
 */
public final class OpenCodeTerminalEditorListener implements FileEditorManagerListener {

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (file instanceof OpenCodeTerminalVirtualFile) {
            OpenCodeTerminalFileEditorProvider.scheduleDisposal(
                    (OpenCodeTerminalVirtualFile) file, source.getProject());
        }
    }
}