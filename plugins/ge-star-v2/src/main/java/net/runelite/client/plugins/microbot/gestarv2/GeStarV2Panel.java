package net.runelite.client.plugins.microbot.gestarv2;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;

/**
 * Sidebar control panel: Execute starts the order-processing script, Stop shuts it down,
 * and the status rows refresh live from the running {@link GeStarV2Script}. Mirrors the
 * layout conventions of other Hub plugin panels (e.g. Auto Bank Stander) so it looks and
 * behaves like the rest of the sidebar.
 */
public class GeStarV2Panel extends PluginPanel {

    private static final Color STOP_RED = new Color(255, 55, 40);

    private final GeStarV2Plugin plugin;
    private final GeStarV2Script script;
    private final GeStarV2Config config;

    private JButton executeButton;
    private JButton stopButton;
    private JLabel statusValueLabel;
    private JLabel stateValueLabel;
    private JLabel pendingValueLabel;
    private JLabel activeValueLabel;
    private JLabel gpSpentValueLabel;

    private final Timer refreshTimer;

    @Inject
    public GeStarV2Panel(GeStarV2Plugin plugin, GeStarV2Script script, GeStarV2Config config) {
        super();
        this.plugin = plugin;
        this.script = script;
        this.config = config;

        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildTitle());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildButtonRow());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildStatusPanel());

        refreshFromScriptState();

        // The script runs on its own scheduled executor, so the panel polls rather than
        // being pushed updates - cheap enough at a slow interval and avoids threading the
        // script's internals through to Swing on every state change.
        refreshTimer = new Timer(1000, e -> refreshFromScriptState());
        refreshTimer.start();
    }

    private JLabel buildTitle() {
        JLabel title = new JLabel("GE Star V2");
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(FontManager.getRunescapeBoldFont().getSize() * 1.5f));
        return title;
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));

        executeButton = new JButton("Execute");
        executeButton.setFont(FontManager.getRunescapeBoldFont());
        executeButton.setBackground(ColorScheme.BRAND_ORANGE);
        executeButton.setForeground(Color.WHITE);
        executeButton.setFocusPainted(false);
        executeButton.addActionListener(e -> onExecuteClicked());

        stopButton = new JButton("Stop");
        stopButton.setFont(FontManager.getRunescapeBoldFont());
        stopButton.setBackground(STOP_RED);
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.addActionListener(e -> onStopClicked());

        row.add(executeButton);
        row.add(stopButton);
        return row;
    }

    private JPanel buildStatusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        statusValueLabel = new JLabel();
        stateValueLabel = new JLabel();
        pendingValueLabel = new JLabel();
        activeValueLabel = new JLabel();
        gpSpentValueLabel = new JLabel();

        panel.add(statusRow("Status", statusValueLabel));
        panel.add(statusRow("State", stateValueLabel));
        panel.add(statusRow("Pending orders", pendingValueLabel));
        panel.add(statusRow("Active offers", activeValueLabel));
        panel.add(statusRow("GP spent (session)", gpSpentValueLabel));

        return panel;
    }

    private JPanel statusRow(String label, JLabel valueLabel) {
        JPanel row = new JPanel(new java.awt.BorderLayout());
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel left = new JLabel(label);
        left.setFont(FontManager.getRunescapeSmallFont());
        left.setForeground(Color.LIGHT_GRAY);

        valueLabel.setFont(FontManager.getRunescapeSmallFont());
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(left, java.awt.BorderLayout.WEST);
        row.add(valueLabel, java.awt.BorderLayout.EAST);
        return row;
    }

    private void onExecuteClicked() {
        if (script.isRunning()) return;
        plugin.execute();
    }

    private void onStopClicked() {
        if (!script.isRunning()) return;
        plugin.stop();
    }

    public void onScriptStateChanged() {
        SwingUtilities.invokeLater(this::refreshFromScriptState);
    }

    private void refreshFromScriptState() {
        boolean running = script.isRunning();

        executeButton.setEnabled(!running);
        stopButton.setEnabled(running);

        statusValueLabel.setText(running ? "Running" : "Stopped");
        statusValueLabel.setForeground(running ? new Color(80, 200, 120) : Color.LIGHT_GRAY);

        stateValueLabel.setText(script.getState().name());
        pendingValueLabel.setText(String.valueOf(script.getPendingOrderCount()));
        activeValueLabel.setText(String.valueOf(script.getActiveOfferCount()));
        gpSpentValueLabel.setText(String.format("%,d", script.getGpSpentThisSession()));
    }
}
