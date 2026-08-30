package net.runelite.client.plugins.microbot.flipperstar;

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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Sidebar panel: a Scan button for on-demand candidate review (the primary, recommended way
 * to use this plugin), an auto-scan status readout (on/off is a config toggle, not a panel
 * control - see FlipperStarConfig's "Automation" section), and a live list of the last scan's
 * top candidates.
 */
public class FlipperStarPanel extends PluginPanel {

    private static final Color GREEN = new Color(80, 200, 120);
    private static final Color ORANGE = ColorScheme.BRAND_ORANGE;

    private final FlipperStarPlugin plugin;
    private final FlipperStarEngine engine;
    private final FlipperStarConfig config;
    private final GeStarBridge geStarBridge;

    private JButton scanButton;
    private JLabel statusValueLabel;
    private JLabel autoScanValueLabel;
    private JLabel openFlipsValueLabel;
    private JLabel lastScanValueLabel;
    private JPanel candidateListPanel;

    private final Timer refreshTimer;

    @Inject
    public FlipperStarPanel(FlipperStarPlugin plugin, FlipperStarEngine engine, FlipperStarConfig config, GeStarBridge geStarBridge) {
        super();
        this.plugin = plugin;
        this.engine = engine;
        this.config = config;
        this.geStarBridge = geStarBridge;

        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildTitle());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildScanButton());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildStatusPanel());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildCandidateListHeader());

        candidateListPanel = new JPanel();
        candidateListPanel.setLayout(new BoxLayout(candidateListPanel, BoxLayout.Y_AXIS));
        add(candidateListPanel);

        refresh();

        refreshTimer = new Timer(2000, e -> refresh());
        refreshTimer.start();
    }

    private JLabel buildTitle() {
        JLabel title = new JLabel("FlipperStar");
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(FontManager.getRunescapeBoldFont().getSize() * 1.5f));
        return title;
    }

    private JButton buildScanButton() {
        scanButton = new JButton("Scan now");
        scanButton.setFont(FontManager.getRunescapeBoldFont());
        scanButton.setBackground(ORANGE);
        scanButton.setForeground(Color.WHITE);
        scanButton.setFocusPainted(false);
        scanButton.addActionListener(e -> onScanClicked());
        return scanButton;
    }

    private JPanel buildStatusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        statusValueLabel = new JLabel();
        autoScanValueLabel = new JLabel();
        openFlipsValueLabel = new JLabel();
        lastScanValueLabel = new JLabel();

        panel.add(statusRow("GE Star V2", statusValueLabel));
        panel.add(statusRow("Auto-scan", autoScanValueLabel));
        panel.add(statusRow("Open flips", openFlipsValueLabel));
        panel.add(statusRow("Last scan", lastScanValueLabel));

        return panel;
    }

    private JPanel statusRow(String label, JLabel valueLabel) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel left = new JLabel(label);
        left.setFont(FontManager.getRunescapeSmallFont());
        left.setForeground(Color.LIGHT_GRAY);

        valueLabel.setFont(FontManager.getRunescapeSmallFont());
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(left, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);
        return row;
    }

    private JLabel buildCandidateListHeader() {
        JLabel header = new JLabel("Last scan candidates");
        header.setFont(FontManager.getRunescapeBoldFont());
        header.setForeground(Color.WHITE);
        return header;
    }

    private void onScanClicked() {
        scanButton.setEnabled(false);
        scanButton.setText("Scanning...");
        // Scanning does its own HTTP call + reflection, no game-thread work - safe to run
        // directly off the EDT via a background thread so the panel doesn't freeze while it
        // waits on the scoring service.
        new Thread(() -> {
            plugin.scanNow();
            SwingUtilities.invokeLater(() -> {
                scanButton.setEnabled(true);
                scanButton.setText("Scan now");
                refresh();
            });
        }, "FlipperStar-ManualScan").start();
    }

    public void refresh() {
        SwingUtilities.invokeLater(this::refreshInternal);
    }

    private void refreshInternal() {
        boolean geStarAvailable = geStarBridge.isAvailable();
        statusValueLabel.setText(geStarAvailable ? "Running" : "Not running");
        statusValueLabel.setForeground(geStarAvailable ? GREEN : Color.LIGHT_GRAY);

        boolean autoScan = config.autoScanEnabled();
        autoScanValueLabel.setText(autoScan ? "On (every " + config.autoScanIntervalMinutes() + "m)" : "Off");
        autoScanValueLabel.setForeground(autoScan ? GREEN : Color.LIGHT_GRAY);

        openFlipsValueLabel.setText(engine.getOpenFlipCount() + " / " + config.maxOpenFlips());

        long lastScan = engine.getLastScanTimestamp();
        lastScanValueLabel.setText(lastScan == 0 ? "Never" : new SimpleDateFormat("HH:mm:ss").format(new Date(lastScan)));

        showCandidates(engine.getLastScanCandidates());
    }

    private void showCandidates(List<Candidate> candidates) {
        candidateListPanel.removeAll();

        if (candidates.isEmpty()) {
            JLabel empty = new JLabel("No candidates from last scan");
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            candidateListPanel.add(empty);
        } else {
            for (Candidate candidate : candidates) {
                candidateListPanel.add(buildCandidateRow(candidate));
                candidateListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        candidateListPanel.revalidate();
        candidateListPanel.repaint();
    }

    private JPanel buildCandidateRow(Candidate candidate) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(candidate.getItemName());
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLabel = new JLabel(String.format(
            "%.1f%% margin, %.1f gp/unit, limit %s",
            candidate.getPredictedMarginPct() * 100,
            candidate.getAbsoluteMarginGp(),
            candidate.getGeLimit() != null ? String.valueOf(candidate.getGeLimit()) : "?"));
        detailLabel.setFont(FontManager.getRunescapeSmallFont());
        detailLabel.setForeground(ORANGE);
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(titleLabel);
        row.add(detailLabel);
        return row;
    }
}
