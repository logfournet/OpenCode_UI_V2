package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import ai.opencode.ide.jetbrains.session.SessionInfo;
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLauncher;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Lightweight tool-window panel: connection status, session picker,
 * a prompt composer, and actions to open the diff viewer / terminal.
 */
public final class OpenCodeToolWindow extends JPanel implements Disposable {

    private final Project project;
    private final OpenCodeService service;

    private final JBLabel statusLabel = new JBLabel("Not connected");
    private final JComboBox<String> sessionCombo = new JComboBox<>();
    private final JBTextField promptField = new JBTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton diffsButton = new JButton("Review Diffs");
    private final JButton terminalButton = new JButton("Terminal");
    private final JButton reconnectButton = new JButton("Reconnect");

    private boolean refreshingSessions;
    private boolean lastConnected;

    public OpenCodeToolWindow(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.service = ServiceManager.getService(project, OpenCodeService.class);

        buildUi();
        wireActions();
        service.addListener(this::refreshStatus);
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(JBUI.Borders.empty(4));
        top.add(statusLabel, BorderLayout.WEST);
        top.add(reconnectButton, BorderLayout.EAST);

        JPanel middle = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        middle.setBorder(JBUI.Borders.empty(4));
        sessionCombo.setPreferredSize(new Dimension(220, sessionCombo.getPreferredSize().height));
        middle.add(sessionCombo, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(diffsButton);
        buttons.add(terminalButton);
        middle.add(buttons, BorderLayout.EAST);

        JPanel prompt = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        prompt.setBorder(JBUI.Borders.empty(4));
        prompt.add(promptField, BorderLayout.CENTER);
        JPanel promptButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        promptButtons.add(sendButton);
        promptButtons.add(stopButton);
        prompt.add(promptButtons, BorderLayout.EAST);

        JLabel hint = new JBLabel("<html><small>Type a prompt and press Send. Select code + Ctrl+\\ to send it as context.</small></html>");
        hint.setBorder(JBUI.Borders.empty(0, 4, 4, 4));

        JPanel south = new JPanel(new BorderLayout());
        south.add(prompt, BorderLayout.CENTER);
        south.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private void wireActions() {
        sendButton.addActionListener(e -> sendPrompt());
        promptField.addActionListener(e -> sendPrompt());
        stopButton.addActionListener(e -> service.interrupt());
        diffsButton.addActionListener(e -> openDiffs());
        terminalButton.addActionListener(e -> OpenCodeTerminalLauncher.open(project));
        reconnectButton.addActionListener(e -> service.reconnect());
        sessionCombo.addActionListener(e -> {
            if (refreshingSessions) return;
            Object sel = sessionCombo.getSelectedItem();
            if (sel instanceof String s && !s.isBlank()) {
                String id = extractId(s);
                if (id != null) service.setSession(id);
            }
        });
    }

    private String extractId(String display) {
        // Display is "title (ses_...)" or "ses_..."
        int i = display.indexOf(" (");
        if (i > 0) {
            String tail = display.substring(i + 2);
            if (tail.endsWith(")")) return tail.substring(0, tail.length() - 1);
        }
        return display.isBlank() ? null : display;
    }

    private void sendPrompt() {
        String text = promptField.getText().trim();
        if (text.isEmpty()) return;
        promptField.setText("");
        if (!service.isConnected()) service.connect();
        service.sendPrompt(text, null);
    }

    private void openDiffs() {
        if (service.getLastDiffs().isEmpty()) {
            service.refreshDiffs();
        } else {
            new ai.opencode.ide.jetbrains.diff.DiffViewerService(project).showDiffs(service.getLastDiffs());
        }
    }

    private void refreshStatus() {
        boolean connected = service.isConnected();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            statusLabel.setText(service.getStatusText());
            if (connected) {
                statusLabel.setForeground(UIManager.getColor("Label.foreground"));
            } else {
                statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            if (connected && !lastConnected) {
                refreshSessionsAsync();
            }
            lastConnected = connected;
        });
    }

    /** Loads the session list in the background (never block the EDT). */
    private void refreshSessionsAsync() {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (project.isDisposed()) return;
            List<SessionInfo> sessions = service.listSessions();
            String selected = service.getSessionId();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                refreshingSessions = true;
                try {
                    sessionCombo.removeAllItems();
                    for (SessionInfo s : sessions) {
                        sessionCombo.addItem(s.displayName() + " (" + s.id + ")");
                    }
                    if (selected != null) {
                        for (int i = 0; i < sessionCombo.getItemCount(); i++) {
                            if (sessionCombo.getItemAt(i).contains(selected)) {
                                sessionCombo.setSelectedIndex(i);
                                break;
                            }
                        }
                    }
                } finally {
                    refreshingSessions = false;
                }
            });
        });
    }

    @Override
    public void dispose() {
        service.removeListener(this::refreshStatus);
    }
}
