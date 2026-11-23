package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Auto-connects the OpenCode service when a project opens,
 * controlled by the "Auto-connect" setting.
 */
public final class OpenCodeStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!project.isDisposed()
                && PropertiesComponent.getInstance(project).getBoolean(OpenCodeSettingsConfigurable.AUTO_CONNECT_KEY, true)) {
            project.getService(OpenCodeService.class).autoConnect();
        }
        return Unit.INSTANCE;
    }
}
