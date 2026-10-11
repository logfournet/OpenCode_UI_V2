package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Auto-connects the OpenCode service when a project opens,
 * controlled by the "Auto-connect" setting.
 */
public final class OpenCodeStartupActivity implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        if (project.isDisposed()) return;
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        if (pc.getBoolean(OpenCodeSettingsConfigurable.AUTO_CONNECT_KEY, true)) {
            OpenCodeService svc = ServiceManager.getService(project, OpenCodeService.class);
            svc.autoConnect();
        }
    }
}
