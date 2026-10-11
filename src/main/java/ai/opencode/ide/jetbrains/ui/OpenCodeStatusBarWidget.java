package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget that shows the OpenCode connection state.
 * Clicking opens or focuses the OpenCode tool window.
 */
public final class OpenCodeStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private final Project project;
    private final OpenCodeService service;
    private volatile StatusBar statusBar;
    private final Runnable refresh = this::refreshWidget;
    private final javax.swing.Timer spinner;
    private int frame;

    private static final String[] FRAMES = {"\u280B", "\u2819", "\u2839", "\u2838", "\u283C",
            "\u2834", "\u2826", "\u2827", "\u2807", "\u280F"};

    public OpenCodeStatusBarWidget(@NotNull Project project) {
        this.project = project;
        this.service = ServiceManager.getService(project, OpenCodeService.class);
        // Animate the spinner while a turn is running.
        this.spinner = new javax.swing.Timer(120, e -> {
            if (service.isBusy()) {
                frame = (frame + 1) % FRAMES.length;
                refreshWidget();
            }
        });
    }

    @Override
    public @NotNull @NonNls String ID() {
        return "OpenCodeStatusBar";
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        service.addListener(refresh);
        if (!spinner.isRunning()) spinner.start();
    }

    @Override
    public void dispose() {
        spinner.stop();
        service.removeListener(refresh);
        statusBar = null;
    }

    private void refreshWidget() {
        StatusBar sb = statusBar;
        if (sb == null || project.isDisposed()) return;
        try {
            sb.updateWidget(ID());
        } catch (Exception ignored) {
            // Status bar may already be gone during shutdown.
        }
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return this;
    }

    // ---- TextPresentation ----

    @Override
    public @NotNull String getText() {
        if (service.isBusy()) return FRAMES[frame] + " OpenCode";
        if (!service.isConnected()) return "\u25CB OpenCode";
        return "\u25C9 OpenCode";
    }

    @Override
    public @NotNull String getTooltipText() {
        return service.getStatusText();
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> service.focusOrCreateTerminal();
    }
}
