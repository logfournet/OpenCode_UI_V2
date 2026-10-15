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
    /** How OpenCode v2 TUI session tabs are shared. Defaults to per-directory. */
    public static final String TAB_SCOPE_KEY = PREFIX + "tabScope";
    /**
     * Terminal scrollback size in lines, applied to the IDE advanced setting
     * {@code terminal.buffer.max.lines.count}. The sentinel
     * {@link #UNLIMITED_TERMINAL_BUFFER_LINES} means "no line limit" and is the
     * default, so long OpenCode output is never trimmed (the buffer only grows
     * with lines that are actually produced).
     */
    public static final String TERMINAL_BUFFER_KEY = PREFIX + "terminalBufferLines";
    /** Sentinel stored when the user keeps the whole scrollback (no line limit). */
    public static final int UNLIMITED_TERMINAL_BUFFER_LINES = 0;
    /** Default = unlimited. */
    public static final int DEFAULT_TERMINAL_BUFFER_LINES = UNLIMITED_TERMINAL_BUFFER_LINES;
    /** Finite value used by the spinner when the user unchecks "Unlimited". */
    public static final int DEFAULT_FINITE_TERMINAL_BUFFER_LINES = 100000;
    public static final int MIN_TERMINAL_BUFFER_LINES = 1000;
    public static final int MAX_TERMINAL_BUFFER_LINES = 1000000;

    private final Project project;
    private JPanel root;
    private JBTextField binaryPathField;
    private JButton browseButton;
    private JBCheckBox autoConnectCheck;
    private JComboBox<String> tabScopeCombo;
    private JSpinner terminalBufferSpinner;
    private JBCheckBox unlimitedBufferCheck;

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

        // Tab scope: keep TUI session tabs per working directory (unique per
        // project) instead of sharing them globally across every IDE/instance.
        c.gridx = 0; c.gridy = 2; c.gridwidth = 1; c.weightx = 0;
        root.add(new JLabel("Tab scope:"), c);

        tabScopeCombo = new JComboBox<>(new String[]{"Per project (cwd)", "Global (shared)"});
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        root.add(tabScopeCombo, c);

        // Terminal scrollback: raise/remove the IDE terminal history cap so long
        // agent output is never trimmed. Shared with every terminal in the IDE.
        c.gridx = 0; c.gridy = 3; c.gridwidth = 1; c.weightx = 0;
        root.add(new JLabel("Terminal scrollback:"), c);

        terminalBufferSpinner = new JSpinner(new SpinnerNumberModel(
                DEFAULT_FINITE_TERMINAL_BUFFER_LINES, MIN_TERMINAL_BUFFER_LINES,
                MAX_TERMINAL_BUFFER_LINES, 1000));
        c.gridx = 1; c.gridwidth = 1; c.weightx = 0;
        root.add(terminalBufferSpinner, c);

        unlimitedBufferCheck = new JBCheckBox("Unlimited (no line limit)");
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0;
        root.add(unlimitedBufferCheck, c);

        unlimitedBufferCheck.addActionListener(e ->
                terminalBufferSpinner.setEnabled(!unlimitedBufferCheck.isSelected()));

        c.gridx = 0; c.gridy = 4; c.gridwidth = 3; c.weightx = 1;
        JLabel bufferNote = new JLabel("<html><small>No line limit by default. Sets the IDE setting "
                + "<code>terminal.buffer.max.lines.count</code> (shared by all IDE terminals). "
                + "RAM is only used as lines are actually produced.</small></html>");
        root.add(bufferNote, c);

        loadSettings();
        return root;
    }

    @Override
    public boolean isModified() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        String storedBinary = pc.getValue(BINARY_PATH_KEY, "");
        boolean storedAuto = pc.getBoolean(AUTO_CONNECT_KEY, true);
        String storedScope = pc.getValue(TAB_SCOPE_KEY, "cwd");
        int storedBuffer = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
        return !binaryPathField.getText().trim().equals(storedBinary)
                || autoConnectCheck.isSelected() != storedAuto
                || !selectedTabScope().equals(storedScope)
                || selectedTerminalBufferLines() != storedBuffer;
    }

    @Override
    public void apply() throws ConfigurationException {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        String oldScope = pc.getValue(TAB_SCOPE_KEY, "cwd");
        int oldBuffer = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
        int newBuffer = selectedTerminalBufferLines();

        pc.setValue(BINARY_PATH_KEY, binaryPathField.getText().trim());
        pc.setValue(AUTO_CONNECT_KEY, autoConnectCheck.isSelected());
        pc.setValue(TAB_SCOPE_KEY, selectedTabScope());
        pc.setValue(TERMINAL_BUFFER_KEY, newBuffer, DEFAULT_TERMINAL_BUFFER_LINES);

        // Reset the cached binary path so next launch picks up the new setting.
        OpenCodeService svc = ServiceManager.getService(project, OpenCodeService.class);
        svc.resetCachedBinary();

        // Tab scope is applied at launch time and the terminal scrollback cap is
        // fixed when the session buffer is created, so the running TUI must be
        // restarted for either change to take effect.
        if (!selectedTabScope().equals(oldScope) || newBuffer != oldBuffer) svc.restartTerminal();
    }

    @Override
    public void reset() {
        loadSettings();
    }

    private String selectedTabScope() {
        return tabScopeCombo != null && tabScopeCombo.getSelectedIndex() == 1 ? "global" : "cwd";
    }

    private int selectedTerminalBufferLines() {
        if (unlimitedBufferCheck != null && unlimitedBufferCheck.isSelected()) {
            return UNLIMITED_TERMINAL_BUFFER_LINES;
        }
        Object v = terminalBufferSpinner == null ? null : terminalBufferSpinner.getValue();
        return v == null ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : ((Number) v).intValue();
    }

    private void loadSettings() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        binaryPathField.setText(pc.getValue(BINARY_PATH_KEY, ""));
        autoConnectCheck.setSelected(pc.getBoolean(AUTO_CONNECT_KEY, true));
        tabScopeCombo.setSelectedIndex("global".equals(pc.getValue(TAB_SCOPE_KEY, "cwd")) ? 1 : 0);

        int lines = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
        boolean unlimited = lines <= 0;
        unlimitedBufferCheck.setSelected(unlimited);
        terminalBufferSpinner.setEnabled(!unlimited);
        terminalBufferSpinner.setValue(unlimited ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : lines);
    }

    @Override
    public String getDisplayName() {
        return "OpenCode";
    }
}
