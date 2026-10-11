package ai.opencode.ide.jetbrains.session;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileMoveEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks file changes during an AI "turn" (session busy -> idle).
 *
 * Captures the original content of every file OpenCode touches so the
 * "before" side of the diff is reliable, and detects user edits so we can
 * warn before overwriting them on Reject.
 */
@Service(Service.Level.PROJECT)
public final class SessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionManager.class);

    private final Project project;

    /** relativePath -> original content captured before OpenCode touched the file */
    private final Map<String, String> capturedBefore = new ConcurrentHashMap<>();

    /** relativePath -> user edited it during the current turn */
    private final Set<String> userEdited = ConcurrentHashMap.newKeySet();

    /** relativePath -> OpenCode created it during the current turn */
    private final Set<String> aiCreated = ConcurrentHashMap.newKeySet();

    private volatile boolean turnActive = false;

    public SessionManager(Project project) {
        this.project = project;
        // Listener registration must happen on the EDT.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
                    @Override
                    public void documentChanged(DocumentEvent event) {
                        if (!turnActive) return;
                        VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                        if (file == null) return;
                        if (isUserCommand()) {
                            userEdited.add(relativize(file.getPath()));
                        }
                    }
                }, this);

                VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileListener() {
                    @Override
                    public void beforeContentsChange(VirtualFileEvent event) {
                        captureBefore(event.getFile());
                    }

                    @Override
                    public void beforeFileDeletion(VirtualFileEvent event) {
                        captureBefore(event.getFile());
                    }

                    @Override
                    public void fileCreated(VirtualFileEvent event) {
                        if (turnActive) aiCreated.add(relativize(event.getFile().getPath()));
                    }
                }, this);
            } catch (Exception e) {
                LOG.debug("listener registration failed", e);
            }
        });
    }

    /** Call when the session goes busy. Returns true if a new turn began. */
    public boolean onTurnStart() {
        if (turnActive) return false;
        turnActive = true;
        capturedBefore.clear();
        userEdited.clear();
        aiCreated.clear();
        return true;
    }

    /** Call when the session becomes idle. */
    public void onTurnEnd() {
        turnActive = false;
    }

    private boolean isUserCommand() {
        try {
            String name = com.intellij.openapi.command.CommandProcessor.getInstance().getCurrentCommandName();
            return name != null && !name.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void captureBefore(VirtualFile file) {
        if (file == null || file.isDirectory() || !turnActive) return;
        String rel = relativize(file.getPath());
        if (capturedBefore.containsKey(rel)) return;
        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            capturedBefore.putIfAbsent(rel, content);
        } catch (Exception e) {
            LOG.debug("capture before failed: " + rel, e);
        }
    }

    private String relativize(String path) {
        String base = project.getBasePath();
        if (base == null) return path.replace('\\', '/');
        String normalizedBase = base.replace('\\', '/');
        if (!normalizedBase.endsWith("/")) normalizedBase += "/";
        String p = path.replace('\\', '/');
        return p.startsWith(normalizedBase) ? p.substring(normalizedBase.length()) : p;
    }

    /** Original content of the file before OpenCode touched it, or null. */
    public String beforeContent(String relativePath) {
        return capturedBefore.get(relativePath);
    }

    public boolean userEdited(String relativePath) {
        return userEdited.contains(relativePath);
    }

    public boolean aiCreated(String relativePath) {
        return aiCreated.contains(relativePath);
    }

    /** Writes content to the file at the given relative path (used by Reject/Accept). */
    public boolean writeFile(String relativePath, String content) {
        try {
            String base = project.getBasePath();
            File target = base == null ? new File(relativePath) : new File(base, relativePath.replace('/', File.separatorChar));
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
            LocalFileSystem.getInstance().refreshIoFiles(java.util.Collections.singleton(target));
            return true;
        } catch (Exception e) {
            LOG.warn("write failed: " + relativePath, e);
            return false;
        }
    }

    public boolean deleteFile(String relativePath) {
        try {
            String base = project.getBasePath();
            File target = base == null ? new File(relativePath) : new File(base, relativePath.replace('/', File.separatorChar));
            boolean ok = target.delete();
            if (ok) LocalFileSystem.getInstance().refreshIoFiles(java.util.Collections.singleton(target));
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public Project getProject() {
        return project;
    }

    @Override
    public void dispose() {
        turnActive = false;
        capturedBefore.clear();
        userEdited.clear();
        aiCreated.clear();
    }
}