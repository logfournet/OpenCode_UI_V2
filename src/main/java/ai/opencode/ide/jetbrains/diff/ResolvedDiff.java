package ai.opencode.ide.jetbrains.diff;

import ai.opencode.ide.jetbrains.session.FileDiff;
import ai.opencode.ide.jetbrains.session.SessionManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;

/**
 * A resolved diff: the FileDiff plus the concrete before/after content.
 */
public final class ResolvedDiff {
    public final FileDiff diff;
    public final String before;
    public final String after;

    public ResolvedDiff(FileDiff diff, String before, String after) {
        this.diff = diff;
        this.before = before;
        this.after = after;
    }
}
