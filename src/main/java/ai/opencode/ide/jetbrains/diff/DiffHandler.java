package ai.opencode.ide.jetbrains.diff;

import ai.opencode.ide.jetbrains.OpenCodePlugin;
import ai.opencode.ide.jetbrains.session.SessionManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Applies Accept/Reject operations on the project's filesystem.
 */
@Service(Service.Level.PROJECT)
public final class DiffHandler {
    private static final Logger LOG = Logger.getInstance(DiffHandler.class);

    private final Project project;

    public DiffHandler(Project project) {
        this.project = project;
    }

    public void accept(ResolvedDiff rd) {
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().runWriteAction(() -> {
                    SessionManager sm = ServiceManager.getService(project, SessionManager.class);
                    boolean ok = sm.writeFile(rd.diff.file, rd.after);
                    if (ok) gitAdd(rd.diff.file);
                    notifyResult("Accepted", rd.diff.file, ok);
                }));
    }

    public void reject(ResolvedDiff rd) {
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().runWriteAction(() -> {
                    SessionManager sm = ServiceManager.getService(project, SessionManager.class);
                    boolean ok = rd.diff.isNewFile()
                            ? sm.deleteFile(rd.diff.file)
                            : sm.writeFile(rd.diff.file, rd.before);
                    notifyResult("Rejected", rd.diff.file, ok);
                }));
    }

    private void gitAdd(String path) {
        try {
            String base = project.getBasePath();
            ProcessBuilder pb = new ProcessBuilder("git", "add", path);
            if (base != null) pb.directory(new File(base));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            LOG.debug("git add failed: " + path, e);
        }
    }

    private void notifyResult(String action, String file, boolean ok) {
        try {
            NotificationType type = ok ? NotificationType.INFORMATION : NotificationType.ERROR;
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(OpenCodePlugin.NOTIFICATION_GROUP)
                    .createNotification(action, file + (ok ? "" : " failed"), type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }
}
