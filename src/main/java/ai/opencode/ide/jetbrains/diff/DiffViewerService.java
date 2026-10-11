package ai.opencode.ide.jetbrains.diff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the IntelliJ native diff viewer for the files an OpenCode turn changed,
 * with Accept / Reject actions.
 */
public final class DiffViewerService {

    private static final Logger LOG = Logger.getInstance(DiffViewerService.class);

    private final Project project;

    public DiffViewerService(Project project) {
        this.project = project;
    }

    public void showDiffs(List<ResolvedDiff> diffs) {
        if (diffs == null || diffs.isEmpty() || project.isDisposed()) return;

        List<DiffRequest> requests = new ArrayList<>();
        for (int i = 0; i < diffs.size(); i++) {
            ResolvedDiff dd = diffs.get(i);
            FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(dd.diff.file);
            String title = "[" + (i + 1) + "/" + diffs.size() + "] " + dd.diff.file;
            DiffRequest request = new SimpleDiffRequest(
                    title,
                    DiffContentFactory.getInstance().create(project, dd.before, fileType),
                    DiffContentFactory.getInstance().create(project, dd.after, fileType),
                    "Before",
                    "After"
            );
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, java.util.Arrays.asList(
                    new AcceptDiffAction(dd),
                    new RejectDiffAction(dd)
            ));
            requests.add(request);
        }

        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(requests);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT);
            }
        });
    }

    private static final class AcceptDiffAction extends AnAction {
        private final ResolvedDiff diff;

        AcceptDiffAction(ResolvedDiff diff) {
            super("Accept (git add)");
            this.diff = diff;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project p = e.getProject();
            if (p == null || p.isDisposed()) return;
            ServiceManager.getService(p, DiffHandler.class).accept(diff);
        }
    }

    private static final class RejectDiffAction extends AnAction {
        private final ResolvedDiff diff;

        RejectDiffAction(ResolvedDiff diff) {
            super("Reject (restore)");
            this.diff = diff;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project p = e.getProject();
            if (p == null || p.isDisposed()) return;
            ServiceManager.getService(p, DiffHandler.class).reject(diff);
        }
    }
}
