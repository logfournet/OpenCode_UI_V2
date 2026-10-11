package ai.opencode.ide.jetbrains.actions;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Sends file path references to the OpenCode terminal (same UX as the 1.x plugin):
 * <ul>
 *   <li>Editor selection → {@code @path#Lstart-end} (reference only, no content)</li>
 *   <li>No selection → {@code @path} for the current file</li>
 *   <li>Project View selection → {@code @path} for each file/directory</li>
 * </ul>
 * If the OpenCode terminal is not open yet, the plugin opens it first and then
 * pastes the reference into the TUI input.
 */
public final class SendSelectionToTerminalAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        OpenCodeService svc = ServiceManager.getService(project, OpenCodeService.class);

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        StringBuilder textToSend = new StringBuilder();

        if (editor != null) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String relativePath = file != null ? getRelativePath(project, file) : null;
            if (relativePath != null) {
                if (editor.getSelectionModel().hasSelection()) {
                    var document = editor.getDocument();
                    int startLine = document.getLineNumber(editor.getSelectionModel().getSelectionStart()) + 1;
                    int endLine = document.getLineNumber(editor.getSelectionModel().getSelectionEnd()) + 1;
                    textToSend.append("@").append(formatPathReference(relativePath))
                            .append("#L").append(startLine).append("-").append(endLine);
                } else {
                    textToSend.append("@").append(formatPathReference(relativePath));
                }
            }
        } else if (virtualFiles != null && virtualFiles.length > 0) {
            VirtualFile focusedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
            for (VirtualFile f : normalizeProjectSelection(virtualFiles, focusedFile)) {
                textToSend.append("@").append(formatPathReference(getRelativePath(project, f))).append(' ');
            }
        }

        if (!textToSend.isEmpty()) {
            svc.focusOrCreateTerminalAndPaste(textToSend.toString().trim() + " ");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean hasEditorContext = editor != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null;
        boolean hasFileSelection = virtualFiles != null && virtualFiles.length > 0;
        e.getPresentation().setEnabledAndVisible(hasEditorContext || hasFileSelection);
    }

    private String getRelativePath(Project project, VirtualFile file) {
        String base = project.getBasePath();
        if (base == null) return file.getPath();
        String filePath = file.getPath().replace('\\', '/');
        String basePath = base.replace('\\', '/');
        if (filePath.startsWith(basePath + "/")) {
            return filePath.substring(basePath.length() + 1);
        }
        return filePath;
    }

    private String formatPathReference(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    private List<VirtualFile> normalizeProjectSelection(VirtualFile[] selectedFiles, VirtualFile focusedFile) {
        LinkedHashMap<String, VirtualFile> all = new LinkedHashMap<>();
        for (VirtualFile f : selectedFiles) all.put(f.getPath(), f);
        if (focusedFile != null && focusedFile.isDirectory()) {
            all.putIfAbsent(focusedFile.getPath(), focusedFile);
        }
        List<VirtualFile> sorted = new ArrayList<>(all.values());
        sorted.sort((a, b) -> a.getPath().length() - b.getPath().length());
        List<VirtualFile> kept = new ArrayList<>();
        for (VirtualFile candidate : sorted) {
            String candidatePath = normalize(candidate.getPath());
            boolean hasParent = kept.stream().anyMatch(parent -> {
                String parentPath = normalize(parent.getPath());
                return candidatePath.equals(parentPath) || candidatePath.startsWith(parentPath + "/");
            });
            if (!hasParent) kept.add(candidate);
        }
        return kept;
    }

    private String normalize(String p) {
        String v = p.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}