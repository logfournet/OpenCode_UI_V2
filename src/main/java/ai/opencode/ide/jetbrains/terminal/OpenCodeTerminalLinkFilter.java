package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes {@code @path}, {@code @"path with spaces"} and {@code @path#Lx-y} clickable
 * inside the OpenCode terminal. Clicking opens the file and highlights the lines.
 */
public final class OpenCodeTerminalLinkFilter implements HyperlinkFilter {

    private static final Logger LOG = Logger.getInstance(OpenCodeTerminalLinkFilter.class);
    private static final Pattern LINK_PATTERN =
            Pattern.compile("@(\"([^\"]+)\"|([^\\s#]+))(#L(\\d+)(-(\\d+))?)?");

    private final Project project;

    private OpenCodeTerminalLinkFilter(Project project) {
        this.project = project;
    }

    @Override
    public LinkResult apply(String line) {
        Matcher matcher = LINK_PATTERN.matcher(line);
        List<LinkResultItem> items = new ArrayList<>();
        while (matcher.find()) {
            String rawPath = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (rawPath == null) continue;
            // Jediterm pads wide (CJK) characters with U+E000; strip them.
            String filePath = rawPath.replace("\uE000", "");
            int startLine = parseInt(matcher.group(5));
            int endLine = parseInt(matcher.group(7));
            if (endLine <= 0) endLine = startLine;
            final int effStart = startLine;
            final int effEnd = endLine;

            LinkInfo info = new LinkInfo(() ->
                    navigateToFile(project, filePath, effStart, effEnd));
            items.add(new LinkResultItem(matcher.start(), matcher.end(), info));
        }
        return items.isEmpty() ? null : new LinkResult(items);
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void navigateToFile(Project project, String filePath, int startLine, int endLine) {
        VirtualFile file = resolveFile(project, filePath);
        if (file == null) {
            LOG.warn("File not found: " + filePath);
            return;
        }
        OpenFileDescriptor descriptor = startLine > 0
                ? new OpenFileDescriptor(project, file, Math.max(startLine, 1) - 1, 0)
                : new OpenFileDescriptor(project, file);

        ApplicationManager.getApplication().invokeLater(() -> {
            var editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
            if (editor != null && startLine > 0) {
                var document = editor.getDocument();
                int startIndex = Math.max(startLine - 1, 0);
                startIndex = Math.min(startIndex, document.getLineCount() - 1);
                int endIndex = (endLine > 0 ? endLine : startLine) - 1;
                endIndex = Math.max(endIndex, startIndex);
                endIndex = Math.min(endIndex, document.getLineCount() - 1);
                int startOffset = document.getLineStartOffset(startIndex);
                int endOffset = document.getLineEndOffset(endIndex);
                editor.getSelectionModel().setSelection(startOffset, endOffset);
                editor.getCaretModel().moveToOffset(startOffset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        });
    }

    private VirtualFile resolveFile(Project project, String filePath) {
        String resolved = resolveProjectPath(project, filePath);
        if (resolved == null) return null;
        return LocalFileSystem.getInstance().findFileByPath(resolved.replace('\\', '/'));
    }

    private String resolveProjectPath(Project project, String maybeRelative) {
        Path p = Paths.get(maybeRelative);
        if (p.isAbsolute()) {
            return Files.exists(p) ? p.toAbsolutePath().toString() : null;
        }
        Path base = Paths.get(project.getBasePath() != null ? project.getBasePath() : "");
        Path candidate = base.resolve(p).normalize();
        return Files.exists(candidate) ? candidate.toString() : null;
    }

    public static void install(Project project, ShellTerminalWidget widget) {
        widget.addHyperlinkFilter(new OpenCodeTerminalLinkFilter(project));
    }
}