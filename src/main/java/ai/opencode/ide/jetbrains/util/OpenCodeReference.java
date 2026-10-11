package ai.opencode.ide.jetbrains.util;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Builds OpenCode context references ({@code @path}, {@code @path#Lx-y}) from an
 * action event, sharing the exact format used by the "Add to OpenCode Terminal"
 * actions so code actions can append a reference to their prompt.
 */
public final class OpenCodeReference {

    private OpenCodeReference() {}

    /** Build a reference string from the current editor/project-view context, or "" if none. */
    public static String from(Project project, AnActionEvent e) {
        if (project == null) return "";
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String rel = file != null ? relative(project, file) : null;
            if (rel != null) {
                if (editor.getSelectionModel().hasSelection()) {
                    var doc = editor.getDocument();
                    int start = doc.getLineNumber(editor.getSelectionModel().getSelectionStart()) + 1;
                    int end = doc.getLineNumber(editor.getSelectionModel().getSelectionEnd()) + 1;
                    return "@" + fmt(rel) + "#L" + start + "-" + end;
                }
                return "@" + fmt(rel);
            }
        }
        VirtualFile[] vfs = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (vfs != null && vfs.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (VirtualFile f : normalize(vfs)) {
                String rel = relative(project, f);
                if (rel != null) sb.append('@').append(fmt(rel)).append(' ');
            }
            return sb.toString().trim();
        }
        return "";
    }

    public static String relative(Project project, VirtualFile file) {
        String base = project.getBasePath();
        if (base == null) return file.getPath();
        String filePath = file.getPath().replace('\\', '/');
        String basePath = base.replace('\\', '/');
        if (filePath.startsWith(basePath + "/")) return filePath.substring(basePath.length() + 1);
        return filePath;
    }

    /** Quote paths that contain spaces so the TUI parses them as one token. */
    public static String fmt(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    private static List<VirtualFile> normalize(VirtualFile[] selected) {
        LinkedHashMap<String, VirtualFile> all = new LinkedHashMap<>();
        for (VirtualFile f : selected) all.put(f.getPath(), f);
        List<VirtualFile> sorted = new ArrayList<>(all.values());
        sorted.sort((a, b) -> a.getPath().length() - b.getPath().length());
        List<VirtualFile> kept = new ArrayList<>();
        for (VirtualFile candidate : sorted) {
            String cp = normalizePath(candidate.getPath());
            boolean hasParent = kept.stream().anyMatch(parent -> {
                String pp = normalizePath(parent.getPath());
                return cp.equals(pp) || cp.startsWith(pp + "/");
            });
            if (!hasParent) kept.add(candidate);
        }
        return kept;
    }

    private static String normalizePath(String p) {
        String v = p.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
