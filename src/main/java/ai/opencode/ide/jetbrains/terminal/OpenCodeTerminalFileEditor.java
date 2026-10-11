package ai.opencode.ide.jetbrains.terminal;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/**
 * FileEditor that wraps an OpenCode terminal widget for display in the editor
 * area, giving the same "tab" experience as the 1.x plugin.
 */
public final class OpenCodeTerminalFileEditor extends UserDataHolderBase implements FileEditor {

    private final Project project;
    private final OpenCodeTerminalVirtualFile virtualFile;
    private final ShellTerminalWidget terminalWidget;

    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final JLabel placeholder =
            new JLabel("OpenCode terminal disconnected. Please close and reopen.", SwingConstants.CENTER);

    public OpenCodeTerminalFileEditor(Project project,
                                      OpenCodeTerminalVirtualFile virtualFile,
                                      @Nullable ShellTerminalWidget terminalWidget) {
        this.project = project;
        this.virtualFile = virtualFile;
        this.terminalWidget = terminalWidget;
    }

    @NotNull
    @Override
    public JComponent getComponent() {
        return terminalWidget != null ? terminalWidget.getComponent() : placeholder;
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return terminalWidget != null ? terminalWidget.getPreferredFocusableComponent() : null;
    }

    @NotNull
    @Override
    public String getName() {
        return "OpenCode Terminal";
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return !disposed.get() && !project.isDisposed();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    @Nullable
    @Override
    public BackgroundEditorHighlighter getBackgroundHighlighter() {
        return null;
    }

    @Nullable
    @Override
    public FileEditorLocation getCurrentLocation() {
        return null;
    }

    @Override
    public void dispose() {
        // Do NOT dispose the terminal widget here: moving/splitting a tab disposes
        // and recreates the editor. Widget lifecycle is owned by the provider.
        if (disposed.compareAndSet(false, true)) {
            for (PropertyChangeListener l : propertyChangeSupport.getPropertyChangeListeners()) {
                propertyChangeSupport.removePropertyChangeListener(l);
            }
        }
    }

    @NotNull
    @Override
    public VirtualFile getFile() {
        return virtualFile;
    }
}