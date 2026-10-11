package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.util.ServerConnection;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Dialog for manually specifying the OpenCode server address + password.
 * Pre-filled from service.json discovery when available.
 */
public final class OpenCodeConnectDialog extends DialogWrapper {

    private final JBTextField urlField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();

    private OpenCodeConnectDialog(Project project, ServerConnection suggested) {
        super(project, true);
        setTitle("Connect to OpenCode Server");
        if (suggested != null) {
            urlField.setText(suggested.url);
            if (suggested.password != null) passwordField.setText(suggested.password);
        } else {
            urlField.setText("http://127.0.0.1:PORT");
        }
        init();
    }

    public static void show(Project project, Consumer<ServerConnection> onResult) {
        ServerConnection suggested = ServerConnection.discover();
        OpenCodeConnectDialog dlg = new OpenCodeConnectDialog(project, suggested);
        if (dlg.showAndGet()) {
            onResult.accept(dlg.buildConnection());
        }
    }

    private ServerConnection buildConnection() {
        String url = urlField.getText().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        return new ServerConnection(url, new String(passwordField.getPassword()), 0, null);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        panel.add(new JBLabel("Server URL (host:port):"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(urlField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel("Password:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(passwordField, c);

        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return urlField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return new ValidationInfo("Server URL must not be empty", urlField);
        return null;
    }
}
