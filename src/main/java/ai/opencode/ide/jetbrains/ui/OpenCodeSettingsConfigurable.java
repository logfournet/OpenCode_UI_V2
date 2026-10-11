package ai.opencode.ide.jetbrains.ui;

import ai.opencode.ide.jetbrains.service.OpenCodeService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBCheckBox;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings page for the OpenCode plugin.
 * Configured per-project via {@link PropertiesComponent}.
 */
public final class OpenCodeSettingsConfigurable implements Configurable {

    public static final String PREFIX = "opencode.";
    public static final String BINARY_PATH_KEY = PREFIX + "binaryPath";
    public static final String AUTO_CONNECT_KEY = PREFIX + "autoConnect";

    private final Project project;
    private JPanel root;
    private JBTextField binaryPathField;
    private JButton browseButton;
    private JBCheckBox autoConnectCheck;

    public OpenCodeSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable JComponent createComponent() {
        root = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        // Binary path
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        root.add(new JLabel("OpenCode binary:"), c);

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect");
        c.gridx = 1; c.weightx = 1;
        root.add(binaryPathField, c);

        browseButton = new JButton("...");
        c.gridx = 2; c.weightx = 0;
        root.add(browseButton, c);

        browseButton.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setDialogTitle("Select OpenCode Binary");
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                binaryPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // Auto-connect
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3;
        autoConnectCheck = new JBCheckBox("Auto-connect to OpenCode when project opens");
        root.add(autoConnectCheck, c);

        loadSettings();
        return root;
    }

    @Override
    public boolean isModified() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        String storedBinary = pc.getValue(BINARY_PATH_KEY, "");
        boolean storedAuto = pc.getBoolean(AUTO_CONNECT_KEY, true);
        return !binaryPathField.getText().trim().equals(storedBinary)
                || autoConnectCheck.isSelected() != storedAuto;
    }

    @Override
    public void apply() throws ConfigurationException {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        pc.setValue(BINARY_PATH_KEY, binaryPathField.getText().trim());
        pc.setValue(AUTO_CONNECT_KEY, autoConnectCheck.isSelected());
        // Reset the cached binary path so next launch picks up the new setting.
        OpenCodeService svc = ServiceManager.getService(project, OpenCodeService.class);
        svc.resetCachedBinary();
    }

    @Override
    public void reset() {
        loadSettings();
    }

    private void loadSettings() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        binaryPathField.setText(pc.getValue(BINARY_PATH_KEY, ""));
        autoConnectCheck.setSelected(pc.getBoolean(AUTO_CONNECT_KEY, true));
    }

    @Override
    public String getDisplayName() {
        return "OpenCode";
    }
}
