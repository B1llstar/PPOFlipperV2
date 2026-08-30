package net.runelite.client.plugins.microbot.gestarv2;

import net.runelite.client.plugins.microbot.gestarv2.portfolio.GeStarPortfolio;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;

/**
 * Sidebar control panel: an add-order form feeds the shared {@link GeStarOrderQueue}, a live
 * list below shows every order with its current status/fill, and Execute/Stop drive the
 * script. This is the only place orders are entered - there's no separate config screen for
 * order lists, so what's queued/active/done is always visible in one place.
 */
public class GeStarV2Panel extends PluginPanel {

    private static final Color STOP_RED = new Color(255, 55, 40);
    private static final Color DONE_GREEN = new Color(80, 200, 120);
    private static final Color FAILED_RED = new Color(220, 90, 80);

    private final GeStarV2Plugin plugin;
    private final GeStarV2Script script;
    private final GeStarOrderQueue queue;
    private final GeStarPortfolio portfolio;

    private JButton executeButton;
    private JButton stopButton;
    private JLabel statusValueLabel;
    private JLabel stateValueLabel;
    private JLabel gpSpentValueLabel;
    private JLabel realizedPnlValueLabel;

    private JTextField itemNameField;
    private JSpinner quantitySpinner;
    private JSpinner priceSpinner;
    private JComboBox<GrandExchangeAction> actionCombo;

    private JPanel orderListPanel;

    private final Timer refreshTimer;

    @Inject
    public GeStarV2Panel(GeStarV2Plugin plugin, GeStarV2Script script, GeStarOrderQueue queue, GeStarPortfolio portfolio) {
        super();
        this.plugin = plugin;
        this.script = script;
        this.queue = queue;
        this.portfolio = portfolio;

        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildTitle());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildButtonRow());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildStatusPanel());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildAddOrderForm());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildOrderListHeader());

        orderListPanel = new JPanel();
        orderListPanel.setLayout(new BoxLayout(orderListPanel, BoxLayout.Y_AXIS));
        add(orderListPanel);

        queue.addListener(() -> SwingUtilities.invokeLater(this::refreshOrderList));

        refreshFromScriptState();
        refreshOrderList();

        // The script runs on its own scheduled executor, so status/state text is polled
        // rather than pushed - cheap at a slow interval. The order list itself repaints
        // on the queue's own change listener instead, so fills show up immediately.
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
        gpSpentValueLabel = new JLabel();
        realizedPnlValueLabel = new JLabel();

        panel.add(statusRow("Status", statusValueLabel));
        panel.add(statusRow("State", stateValueLabel));
        panel.add(statusRow("GP spent (session)", gpSpentValueLabel));
        panel.add(statusRow("Realized P&L (all-time)", realizedPnlValueLabel));

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

    private JPanel buildAddOrderForm() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;

        JLabel formTitle = new JLabel("Add order");
        formTitle.setFont(FontManager.getRunescapeBoldFont());
        formTitle.setForeground(Color.WHITE);
        panel.add(formTitle, c);

        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 1;
        itemNameField = new JTextField();
        itemNameField.setToolTipText("Item name");
        panel.add(labeled("Item", itemNameField), c);
        c.gridx = 1;
        actionCombo = new JComboBox<>(GrandExchangeAction.values());
        actionCombo.removeItem(GrandExchangeAction.COLLECT);
        panel.add(labeled("Action", actionCombo), c);

        c.gridx = 0;
        c.gridy++;
        quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 2_000_000_000, 1));
        panel.add(labeled("Quantity", quantitySpinner), c);
        c.gridx = 1;
        priceSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 2_000_000_000, 1));
        panel.add(labeled("Price ea.", priceSpinner), c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        JButton addButton = new JButton("Add to queue");
        addButton.setFont(FontManager.getRunescapeFont());
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.setFocusPainted(false);
        addButton.addActionListener(e -> onAddOrderClicked());
        panel.add(addButton, c);

        return panel;
    }

    private JPanel labeled(String label, Component field) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);

        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(FontManager.getRunescapeSmallFont());
        labelComponent.setForeground(Color.LIGHT_GRAY);
        labelComponent.setAlignmentX(Component.LEFT_ALIGNMENT);

        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));

        wrapper.add(labelComponent);
        wrapper.add(field);
        return wrapper;
    }

    private JLabel buildOrderListHeader() {
        JLabel header = new JLabel("Order queue");
        header.setFont(FontManager.getRunescapeBoldFont());
        header.setForeground(Color.WHITE);
        return header;
    }

    private void onAddOrderClicked() {
        String name = itemNameField.getText() == null ? "" : itemNameField.getText().trim();
        if (name.isEmpty()) return;

        int quantity = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        GrandExchangeAction action = (GrandExchangeAction) actionCombo.getSelectedItem();
        if (action == null) action = GrandExchangeAction.BUY;

        queue.add(new GeStarOrder(action, name, quantity, price));
        itemNameField.setText("");
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
        statusValueLabel.setForeground(running ? DONE_GREEN : Color.LIGHT_GRAY);

        stateValueLabel.setText(script.getState().name());
        gpSpentValueLabel.setText(String.format("%,d", script.getGpSpentThisSession()));

        long realizedPnl = portfolio.getTotalRealizedProfit();
        realizedPnlValueLabel.setText(String.format("%,d gp", realizedPnl));
        realizedPnlValueLabel.setForeground(realizedPnl >= 0 ? DONE_GREEN : FAILED_RED);
    }

    private void refreshOrderList() {
        orderListPanel.removeAll();

        List<GeStarOrder> orders = queue.getAll();
        if (orders.isEmpty()) {
            JLabel empty = new JLabel("No orders queued");
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            orderListPanel.add(empty);
        } else {
            for (GeStarOrder order : orders) {
                orderListPanel.add(buildOrderRow(order));
                orderListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        orderListPanel.revalidate();
        orderListPanel.repaint();
    }

    private JPanel buildOrderRow(GeStarOrder order) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        String verb = order.getAction() == GrandExchangeAction.SELL ? "Sell" : "Buy";
        JLabel titleLabel = new JLabel(String.format("%s %,dx %s", verb, order.getQuantity(), order.getItemName()));
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLabel = new JLabel(String.format("@ %,d gp - %s", order.getPrice(), statusText(order)));
        detailLabel.setFont(FontManager.getRunescapeSmallFont());
        detailLabel.setForeground(statusColor(order));
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(detailLabel);

        JButton removeButton = new JButton("x");
        removeButton.setFont(FontManager.getRunescapeSmallFont());
        removeButton.setFocusPainted(false);
        removeButton.setMargin(new Insets(0, 6, 0, 6));
        removeButton.setEnabled(order.getStatus() == GeStarOrder.Status.QUEUED || order.getStatus() == GeStarOrder.Status.DONE
            || order.getStatus() == GeStarOrder.Status.SKIPPED || order.getStatus() == GeStarOrder.Status.FAILED);
        removeButton.addActionListener(e -> queue.remove(order.getId()));

        JPanel removeWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        removeWrapper.setOpaque(false);
        removeWrapper.add(removeButton);

        row.add(textPanel, BorderLayout.CENTER);
        row.add(removeWrapper, BorderLayout.EAST);
        return row;
    }

    private String statusText(GeStarOrder order) {
        switch (order.getStatus()) {
            case QUEUED:
                return "Queued";
            case SUBMITTED:
                return String.format("Active - %d%% filled", order.getProgressPercentage());
            case DONE:
                return "Done";
            case SKIPPED:
                return "Skipped: " + order.getStatusDetail();
            case FAILED:
                return "Failed: " + order.getStatusDetail();
            default:
                return order.getStatus().name();
        }
    }

    private Color statusColor(GeStarOrder order) {
        switch (order.getStatus()) {
            case DONE:
                return DONE_GREEN;
            case SKIPPED:
            case FAILED:
                return FAILED_RED;
            case SUBMITTED:
                return ColorScheme.BRAND_ORANGE;
            default:
                return Color.LIGHT_GRAY;
        }
    }
}
