package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.CostBasisEntry;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreClient;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
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
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Optional;

/**
 * Sidebar control panel: an add-order form feeds the shared {@link OrderQueue}, a live list
 * below shows every order with its current status/fill, a portfolio section shows open
 * positions and gold/net-worth, and Execute/Cancel-all drive the script. Mirrors ge-star-v2's
 * {@code GeStarV2Panel} structure closely (this is a manual-first plugin, same as that one), but
 * built independently against this plugin's own classes.
 */
public class PPOFlipperStarPanel extends PluginPanel {

    private static final Color STOP_RED = new Color(255, 55, 40);
    private static final Color DONE_GREEN = new Color(80, 200, 120);
    private static final Color FAILED_RED = new Color(220, 90, 80);

    private final PPOFlipperStarPlugin plugin;
    private final PPOFlipperStarScript script;
    private final OrderQueue queue;
    private final PortfolioManager portfolio;
    private final GoldManager goldManager;
    private final WatchlistManager watchlistManager;
    private final DecisionSuggestions decisionSuggestions;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();

    private JButton executeButton;
    private JButton stopButton;
    private JButton cancelAllButton;
    private JLabel statusValueLabel;
    private JLabel stateValueLabel;
    private JLabel modelUnresponsiveLabel;
    private JLabel gpSpentValueLabel;
    private JLabel realizedPnlValueLabel;
    private JLabel goldValueLabel;
    private JLabel netWorthDeltaValueLabel;

    private JTextField itemNameField;
    private JSpinner quantitySpinner;
    private JSpinner priceSpinner;
    private JComboBox<GrandExchangeAction> actionCombo;
    private JLabel addOrderErrorLabel;

    private JPanel orderListPanel;
    private JPanel portfolioListPanel;
    private JPanel suggestionsListPanel;

    private final Timer refreshTimer;

    @Inject
    public PPOFlipperStarPanel(PPOFlipperStarPlugin plugin, PPOFlipperStarScript script, OrderQueue queue,
                                PortfolioManager portfolio, GoldManager goldManager, WatchlistManager watchlistManager,
                                DecisionSuggestions decisionSuggestions, PPOFlipperStarFirestoreSync firestoreSync) {
        super();
        this.plugin = plugin;
        this.script = script;
        this.queue = queue;
        this.portfolio = portfolio;
        this.goldManager = goldManager;
        this.watchlistManager = watchlistManager;
        this.decisionSuggestions = decisionSuggestions;
        this.firestoreSync = firestoreSync;

        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildTitle());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildButtonRow());
        add(Box.createRigidArea(new Dimension(0, 6)));
        add(buildCancelAllButton());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildStatusPanel());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildSuggestionsHeader());

        suggestionsListPanel = new JPanel();
        suggestionsListPanel.setLayout(new BoxLayout(suggestionsListPanel, BoxLayout.Y_AXIS));
        add(suggestionsListPanel);

        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildSeedWatchlistButton());

        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildAddOrderForm());
        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildOrderListHeader());

        orderListPanel = new JPanel();
        orderListPanel.setLayout(new BoxLayout(orderListPanel, BoxLayout.Y_AXIS));
        add(orderListPanel);

        add(Box.createRigidArea(new Dimension(0, 10)));
        add(buildPortfolioHeader());

        portfolioListPanel = new JPanel();
        portfolioListPanel.setLayout(new BoxLayout(portfolioListPanel, BoxLayout.Y_AXIS));
        add(portfolioListPanel);

        queue.addListener(() -> SwingUtilities.invokeLater(this::refreshOrderList));
        decisionSuggestions.addListener(() -> SwingUtilities.invokeLater(this::refreshSuggestions));

        refreshFromScriptState();
        refreshOrderList();
        refreshPortfolio();
        refreshSuggestions();

        // The script runs on its own scheduled executor, so status/state text is polled rather
        // than pushed - cheap at a slow interval. The order list itself repaints on the queue's
        // own change listener instead, so fills show up immediately.
        refreshTimer = new Timer(1000, e -> {
            refreshFromScriptState();
            refreshPortfolio();
        });
        refreshTimer.start();
    }

    /**
     * Model suggestions section (PROPOSAL.md §2.5/§3.6/§3.7): every actionable proposal from the
     * PPO policy's most recent decision tick still awaiting a human decision, each with its own
     * Confirm/Dismiss buttons. Confirming pushes a brand-new {@link PPOFlipperOrder} onto
     * {@link #queue} through the exact same {@link OrderQueue#add} path a manual right-click/
     * add-order-form order takes (see {@link #onConfirmSuggestionClicked}) - it passes through
     * {@link Guardrails#check} identically to any other order once the script's SUBMITTING_ORDERS
     * state reaches it.
     *
     * <p>When {@code config.autonomousModeEnabled()} is on, a suggestion that clears the
     * confidence threshold is submitted automatically by the script
     * ({@code PPOFlipperStarScript#autonomouslySubmit}, via the identical {@link OrderQueue#add}
     * call) and removed from this list before this panel ever renders it - so a row only ever
     * shows up here when it is genuinely still awaiting a manual decision, whether because
     * autonomous mode is off or because the suggestion didn't clear the confidence threshold.
     */
    private JLabel buildSuggestionsHeader() {
        JLabel header = new JLabel("Model suggestions");
        header.setFont(FontManager.getRunescapeBoldFont());
        header.setForeground(Color.WHITE);
        header.setToolTipText("Proposed actions from the PPO policy's most recent decision tick, awaiting a " +
            "manual decision. Click Confirm to queue one exactly like a manual order. If autonomous mode is " +
            "enabled, suggestions above the confidence threshold submit automatically and never appear here.");
        return header;
    }

    private JLabel buildTitle() {
        JLabel title = new JLabel("PPOFlipperStar");
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

    private JButton buildCancelAllButton() {
        cancelAllButton = new JButton("Cancel all offers");
        cancelAllButton.setFont(FontManager.getRunescapeFont());
        cancelAllButton.setBackground(FAILED_RED);
        cancelAllButton.setForeground(Color.WHITE);
        cancelAllButton.setFocusPainted(false);
        cancelAllButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancelAllButton.setToolTipText("Aborts every active GE offer and collects everything back to inventory/bank");
        cancelAllButton.addActionListener(e -> onCancelAllClicked());
        return cancelAllButton;
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
        goldValueLabel = new JLabel();
        netWorthDeltaValueLabel = new JLabel();

        // Deliberately a standalone banner, not another quiet statusRow among six others - see
        // refreshFromScriptState's javadoc for why: a real incident (the Python inference worker
        // killed and never restarted) left DECIDE ticks silently timing out and defaulting every
        // item to HOLD, visible only as a log line. Hidden entirely (setVisible(false)) unless
        // PPOFlipperStarScript#isModelUnresponsive is true, so it never adds visual noise while
        // everything is actually working.
        modelUnresponsiveLabel = new JLabel("MODEL NOT RESPONDING - check the inference worker");
        modelUnresponsiveLabel.setFont(FontManager.getRunescapeSmallFont());
        modelUnresponsiveLabel.setForeground(FAILED_RED);
        modelUnresponsiveLabel.setHorizontalAlignment(SwingConstants.CENTER);
        modelUnresponsiveLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        modelUnresponsiveLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        modelUnresponsiveLabel.setVisible(false);
        panel.add(modelUnresponsiveLabel);

        panel.add(statusRow("Status", statusValueLabel));
        panel.add(statusRow("State", stateValueLabel));
        panel.add(statusRow("Gold (inv+bank)", goldValueLabel));
        panel.add(statusRow("Net worth Δ (session)", netWorthDeltaValueLabel));
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
        c.gridwidth = 2;
        c.weightx = 1;
        itemNameField = new JTextField();
        itemNameField.setToolTipText("Item name - type to search, or type a full custom name for an item you don't currently hold");
        JButton searchButton = new JButton("Search...");
        searchButton.setFont(FontManager.getRunescapeSmallFont());
        searchButton.addActionListener(e -> onSearchItemClicked());
        JPanel itemRow = new JPanel(new BorderLayout(4, 0));
        itemRow.setOpaque(false);
        itemRow.add(itemNameField, BorderLayout.CENTER);
        itemRow.add(searchButton, BorderLayout.EAST);
        panel.add(labeled("Item", itemRow), c);

        c.gridy++;
        c.gridwidth = 1;
        actionCombo = new JComboBox<>(new GrandExchangeAction[]{GrandExchangeAction.BUY, GrandExchangeAction.SELL});
        panel.add(labeled("Action", actionCombo), c);

        c.gridx = 1;
        quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 2_000_000_000, 1));
        panel.add(labeled("Quantity", quantitySpinner), c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        priceSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 2_000_000_000, 1));
        panel.add(labeled("Price ea.", priceSpinner), c);

        c.gridy++;
        JButton addButton = new JButton("Add to queue");
        addButton.setFont(FontManager.getRunescapeFont());
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.setFocusPainted(false);
        addButton.addActionListener(e -> onAddOrderClicked());
        panel.add(addButton, c);

        c.gridy++;
        addOrderErrorLabel = new JLabel(" ");
        addOrderErrorLabel.setFont(FontManager.getRunescapeSmallFont());
        addOrderErrorLabel.setForeground(FAILED_RED);
        panel.add(addOrderErrorLabel, c);

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

    /**
     * Order-list section header, with a "Clear queue" button alongside it - added after a real
     * live incident where autonomous mode's per-tick re-evaluation of a 300+ item watchlist (with
     * no dedup at the time - see PPOFlipperStarScript.autonomouslySubmit's later fix) queued over
     * 1,500 orders in minutes, with no way to wipe the backlog short of restarting the client
     * entirely. "Cancel all offers" already clears QUEUED orders as a side effect of its main job
     * (aborting live GE offers), but that's a slower, GE-trip-triggering action for a problem that
     * doesn't need one - clearing a QUEUED-only backlog is instant and touches nothing on the GE.
     */
    private JPanel buildOrderListHeader() {
        JLabel header = new JLabel("Order queue");
        header.setFont(FontManager.getRunescapeBoldFont());
        header.setForeground(Color.WHITE);

        JButton clearButton = new JButton("Clear queue");
        clearButton.setFont(FontManager.getRunescapeSmallFont());
        clearButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        clearButton.setForeground(Color.WHITE);
        clearButton.setFocusPainted(false);
        clearButton.setMargin(new Insets(0, 6, 0, 6));
        clearButton.setToolTipText("Removes every QUEUED (not yet submitted) order from the queue. Does NOT touch "
            + "SUBMITTED orders (real offers already live on the GE) - use \"Cancel all offers\" above for those.");
        clearButton.addActionListener(e -> onClearQueueClicked());

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(header, BorderLayout.WEST);
        row.add(clearButton, BorderLayout.EAST);
        return row;
    }

    /**
     * Removes every QUEUED order in one call - deliberately leaves SUBMITTED orders alone (a
     * SUBMITTED order corresponds to a real, live GE offer; silently dropping it from the local
     * queue would make the plugin lose track of something still actually happening in-game -
     * that's what {@code requestCancelAll}/reconciliation exists to handle correctly). Confirms
     * first since this is a bulk, not-easily-undone action, same UX pattern as the watchlist-seed
     * button above.
     */
    private void onClearQueueClicked() {
        List<PPOFlipperOrder> toRemove = queue.getByStatus(PPOFlipperOrder.Status.QUEUED);
        if (toRemove.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Queue has no QUEUED orders to clear.",
                "Clear queue", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            String.format("Remove %d queued order(s)? This does not affect orders already submitted to the GE.",
                toRemove.size()),
            "Clear queue", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        for (PPOFlipperOrder order : toRemove) {
            queue.remove(order.getId());
        }
    }

    private JLabel buildPortfolioHeader() {
        JLabel header = new JLabel("Portfolio (inventory + bank)");
        header.setFont(FontManager.getRunescapeBoldFont());
        header.setForeground(Color.WHITE);
        return header;
    }

    /**
     * The current deployed checkpoint's git commit, used to look up its
     * {@code modelTrainedItems/{gitCommit}} document (PROPOSAL.md's model-versioning note, §3.4).
     * Hardcoded rather than read from {@code data/models/ppo/best.json} at runtime - this plugin
     * has no established, non-fragile way to locate/read a file from the Python-side data
     * directory at a fixed path from inside a sideloaded RuneLite plugin jar (unlike Firestore,
     * which it already talks to for everything else). Update this constant whenever a new
     * checkpoint is deployed; a cleaner long-term fix (reading best.json directly, or a Firestore
     * "current deployed checkpoint" pointer doc) is future work, deliberately not built here to
     * avoid over-engineering a general checkpoint-version-discovery system for this one button.
     */
    private static final String DEPLOYED_CHECKPOINT_GIT_COMMIT = "698392b0ed9101d471a8d7b426fcc57a8a315437";

    /**
     * "Seed watchlist from trained items" (task requirement, not in the original PROPOSAL.md):
     * a deliberate, explicit, one-click bulk action that adds every item id from the currently
     * deployed checkpoint's {@code modelTrainedItems/{gitCommit}} Firestore document (see
     * {@link PPOFlipperStarFirestoreClient#getModelTrainedItems}) to {@link #watchlistManager},
     * skipping ids already present. Deliberately NOT run automatically on plugin startup - doing
     * so would silently and repeatedly change the user's own curated watchlist without them
     * asking, every time the plugin starts. A confirmation dialog gates it since it's a bulk,
     * hard-to-quickly-undo action (up to ~300 items, each would need removing one at a time via
     * right-click/Unwatch otherwise).
     */
    private JButton buildSeedWatchlistButton() {
        JButton button = new JButton("Seed watchlist from trained items");
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setToolTipText("Adds every item the current deployed model checkpoint was trained on to your " +
            "watchlist (up to ~300 items) - the model can only autonomously act on watchlisted items, so this " +
            "widens its universe. Manual add/remove via right-click still works on top of this.");
        button.addActionListener(e -> onSeedWatchlistClicked(button));
        return button;
    }

    private void onSeedWatchlistClicked(JButton button) {
        int confirm = JOptionPane.showConfirmDialog(this,
            "This will add up to 300 items to your watchlist. Continue?",
            "Seed watchlist from trained items", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        button.setEnabled(false);
        button.setText("Seeding...");

        // Firestore reads block on network I/O - never run on the EDT (this click handler).
        Thread seedThread = new Thread(() -> {
            Optional<List<PPOFlipperStarFirestoreClient.TrainedItem>> trainedItems =
                firestoreSync.pullModelTrainedItems(DEPLOYED_CHECKPOINT_GIT_COMMIT);

            int added = 0;
            int total = 0;
            if (trainedItems.isPresent()) {
                total = trainedItems.get().size();
                for (PPOFlipperStarFirestoreClient.TrainedItem item : trainedItems.get()) {
                    if (!watchlistManager.contains(item.itemId)) {
                        watchlistManager.add(item.itemId);
                        added++;
                    }
                }
            }

            final int addedCount = added;
            final int totalCount = total;
            final boolean found = trainedItems.isPresent();
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                button.setText("Seed watchlist from trained items");
                if (!found) {
                    JOptionPane.showMessageDialog(this,
                        "Could not load trained items for checkpoint " + DEPLOYED_CHECKPOINT_GIT_COMMIT
                            + " - cloud sync may be disabled/unreachable, or no such document exists yet.",
                        "Seed watchlist failed", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this,
                        String.format("Added %d new item(s) to the watchlist (%d already watched, %d total in checkpoint).",
                            addedCount, totalCount - addedCount, totalCount),
                        "Seed watchlist complete", JOptionPane.INFORMATION_MESSAGE);
                }
            });
        }, "PPOFlipperStar-SeedWatchlist");
        seedThread.setDaemon(true);
        seedThread.start();
    }

    /**
     * Item-name autocomplete backed by {@link Rs2ItemManager#searchItem}. There is no bulk
     * "list every item name" API on the client, so this is a search-as-you-decide dialog rather
     * than a live-filtering dropdown - satisfies the "type custom item names" requirement
     * (PROPOSAL.md §2.1) for adding a buy on an item not currently held, without needing a
     * dropdown of tens of thousands of item names loaded up front.
     */
    private void onSearchItemClicked() {
        String query = itemNameField.getText() == null ? "" : itemNameField.getText().trim();
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Type at least part of an item name first, then click Search.");
            return;
        }

        List<ItemPrice> results = itemManager.searchItem(query);
        if (results.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No items found matching \"" + query + "\"");
            return;
        }

        JList<String> list = new JList<>(results.stream().map(ItemPrice::getName).toArray(String[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int result = JOptionPane.showConfirmDialog(this, new javax.swing.JScrollPane(list),
            "Select an item", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION && list.getSelectedValue() != null) {
            itemNameField.setText(list.getSelectedValue());
        }
    }

    private void onAddOrderClicked() {
        addOrderErrorLabel.setText(" ");

        String name = itemNameField.getText() == null ? "" : itemNameField.getText().trim();
        if (name.isEmpty()) return;

        int quantity = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        GrandExchangeAction action = (GrandExchangeAction) actionCombo.getSelectedItem();
        if (action == null) action = GrandExchangeAction.BUY;

        String rejection = validateNewOrder(action, name, quantity);
        if (rejection != null) {
            addOrderErrorLabel.setText(rejection);
            return;
        }

        // Rs2ItemManager.getItemId(String) does a plain substring search with no exact-match
        // filter - a real bug found live: typing "Pie dish" silently resolved to "Unfired pie
        // dish" (id 1789) instead of the real "Pie dish" (id 2313), since the latter's name
        // contains the former as a substring and happened to win the underlying map's iteration
        // order. getItemIdByName(name, true) does an equalsIgnoreCase pass (and checks held
        // inventory/bank items by exact name first) instead of taking whatever substring match
        // comes first.
        int itemId = Rs2ItemManager.getItemIdByName(name, true);
        queue.add(new PPOFlipperOrder(action, itemId, name, quantity, price));
        itemNameField.setText("");
    }

    /**
     * Reject at entry, not just at submission time: an order that can never fill (a SELL for an
     * item not actually held, a BUY that would exceed the buy limit) would otherwise sit in the
     * queue until {@link Guardrails#check} rejects it once the script gets to it - confusing to
     * watch "queued" for something that can never resolve. Uses the same live-inventory/ledger
     * reads the submission-time guardrail uses, so the two can never disagree. Returns null if
     * the order is fine to queue.
     */
    private String validateNewOrder(GrandExchangeAction action, String name, int quantity) {
        if (action == GrandExchangeAction.SELL) {
            int held = portfolio.getHeldQuantity(name);
            if (held <= 0) {
                return name + " is not held";
            }
            if (quantity > held) {
                return "Only " + held + "x " + name + " held, can't sell " + quantity;
            }
        }
        return null;
    }

    private void onExecuteClicked() {
        if (script.isRunning()) return;
        plugin.execute();
    }

    private void onStopClicked() {
        if (!script.isRunning()) return;
        plugin.stop();
    }

    /**
     * Clears every still-QUEUED order immediately (no GE interaction needed for those - they
     * were never submitted) and separately requests the script abort/collect everything
     * currently live on the exchange.
     */
    private void onCancelAllClicked() {
        if (script.isCancellingAll()) return;
        for (PPOFlipperOrder order : queue.getByStatus(PPOFlipperOrder.Status.QUEUED)) {
            queue.remove(order.getId());
        }
        plugin.cancelAllOffers();
    }

    public void onScriptStateChanged() {
        SwingUtilities.invokeLater(this::refreshFromScriptState);
    }

    private void refreshFromScriptState() {
        boolean running = script.isRunning();
        boolean cancelling = script.isCancellingAll();

        modelUnresponsiveLabel.setVisible(running && script.isModelUnresponsive());

        executeButton.setEnabled(!running);
        stopButton.setEnabled(running);
        cancelAllButton.setEnabled(!cancelling);
        cancelAllButton.setText(cancelling ? "Cancelling..." : "Cancel all offers");

        statusValueLabel.setText(running ? "Running" : "Stopped");
        statusValueLabel.setForeground(running ? DONE_GREEN : Color.LIGHT_GRAY);

        stateValueLabel.setText(script.getState().name());
        gpSpentValueLabel.setText(String.format("%,d", script.getGpSpentThisSession()));

        goldValueLabel.setText(String.format("%,d gp", goldManager.getTotalGold()));

        long netWorthDelta = goldManager.getSessionNetWorthDelta();
        netWorthDeltaValueLabel.setText(String.format("%+,d gp", netWorthDelta));
        netWorthDeltaValueLabel.setForeground(netWorthDelta >= 0 ? DONE_GREEN : FAILED_RED);

        long realizedPnl = portfolio.getTotalRealizedProfit();
        realizedPnlValueLabel.setText(String.format("%,d gp", realizedPnl));
        realizedPnlValueLabel.setForeground(realizedPnl >= 0 ? DONE_GREEN : FAILED_RED);
    }

    private void refreshOrderList() {
        orderListPanel.removeAll();

        List<PPOFlipperOrder> orders = queue.getAll();
        if (orders.isEmpty()) {
            JLabel empty = new JLabel("No orders queued");
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            orderListPanel.add(empty);
        } else {
            for (PPOFlipperOrder order : orders) {
                orderListPanel.add(buildOrderRow(order));
                orderListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        orderListPanel.revalidate();
        orderListPanel.repaint();
    }

    private JPanel buildOrderRow(PPOFlipperOrder order) {
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

        // Once submitted, show the price actually offered to the GE (which clampToLivePrice may
        // have adjusted away from what was typed - see PPOFlipperOrder.submittedPrice's javadoc),
        // not the originally-requested price - a DONE/SUBMITTED order showing the requested price
        // here made a clamp look like it never happened once the clamp note dropped out of
        // statusText's SKIPPED/FAILED-only history.
        int displayPrice = order.getSubmittedPrice() > 0 ? order.getSubmittedPrice() : order.getPrice();
        String priceLabel = (order.getSubmittedPrice() > 0 && order.getSubmittedPrice() != order.getPrice())
            ? String.format("@ %,d gp (requested %,d gp)", displayPrice, order.getPrice())
            : String.format("@ %,d gp", displayPrice);
        JLabel detailLabel = new JLabel(String.format("%s - %s", priceLabel, statusText(order)));
        detailLabel.setFont(FontManager.getRunescapeSmallFont());
        detailLabel.setForeground(statusColor(order));
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(detailLabel);

        JButton removeButton = new JButton("x");
        removeButton.setFont(FontManager.getRunescapeSmallFont());
        removeButton.setFocusPainted(false);
        removeButton.setMargin(new Insets(0, 6, 0, 6));
        removeButton.setEnabled(order.getStatus() == PPOFlipperOrder.Status.QUEUED || order.getStatus() == PPOFlipperOrder.Status.DONE
            || order.getStatus() == PPOFlipperOrder.Status.SKIPPED || order.getStatus() == PPOFlipperOrder.Status.FAILED);
        removeButton.addActionListener(e -> queue.remove(order.getId()));

        JPanel removeWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        removeWrapper.setOpaque(false);
        removeWrapper.add(removeButton);

        row.add(textPanel, BorderLayout.CENTER);
        row.add(removeWrapper, BorderLayout.EAST);
        return row;
    }

    private String statusText(PPOFlipperOrder order) {
        switch (order.getStatus()) {
            case QUEUED:
                return "Queued";
            case SUBMITTED:
                // A price clamp (if any) is shown in buildOrderRow's price line itself
                // (submittedPrice vs. requested price), not repeated here.
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

    private Color statusColor(PPOFlipperOrder order) {
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

    private void refreshPortfolio() {
        portfolioListPanel.removeAll();

        List<CostBasisEntry> positions = portfolio.getOpenPositions();
        if (positions.isEmpty()) {
            JLabel empty = new JLabel("No open positions");
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            portfolioListPanel.add(empty);
        } else {
            for (CostBasisEntry entry : positions) {
                portfolioListPanel.add(buildPositionRow(entry));
                portfolioListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        portfolioListPanel.revalidate();
        portfolioListPanel.repaint();
    }

    private JPanel buildPositionRow(CostBasisEntry entry) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(String.format("%,dx %s", entry.getQuantityHeld(), portfolio.getItemName(entry.getItemId())));
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLabel = new JLabel(String.format("avg cost %,d gp - realized %+,d gp",
            entry.getAverageCost(), entry.getRealizedProfit()));
        detailLabel.setFont(FontManager.getRunescapeSmallFont());
        detailLabel.setForeground(Color.LIGHT_GRAY);
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(titleLabel);
        row.add(detailLabel);
        return row;
    }

    // ---------------------------------------------------------------------------------------
    // Model suggestions (PROPOSAL.md §2.5/§3.6/§3.7) - shadow mode, always requires a manual
    // Confirm click before anything reaches OrderQueue. See buildSuggestionsHeader's javadoc.
    // ---------------------------------------------------------------------------------------

    private void refreshSuggestions() {
        suggestionsListPanel.removeAll();

        List<PPOFlipperDecision> suggestions = decisionSuggestions.getAll();
        if (suggestions.isEmpty()) {
            JLabel empty = new JLabel("No pending suggestions");
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            suggestionsListPanel.add(empty);
        } else {
            for (PPOFlipperDecision decision : suggestions) {
                suggestionsListPanel.add(buildSuggestionRow(decision));
                suggestionsListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        suggestionsListPanel.revalidate();
        suggestionsListPanel.repaint();
    }

    private JPanel buildSuggestionRow(PPOFlipperDecision decision) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        String verb = decision.getGeAction() == GrandExchangeAction.SELL ? "Sell" : "Buy";
        JLabel titleLabel = new JLabel(String.format("%s %,dx %s", verb, decision.getQuantity(), decision.getItemName()));
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLabel = new JLabel(String.format("@ %,d gp - %s - confidence %.0f%%",
            decision.getPrice(), decision.getActionName(), decision.getConfidence() * 100.0));
        detailLabel.setFont(FontManager.getRunescapeSmallFont());
        detailLabel.setForeground(Color.LIGHT_GRAY);
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(detailLabel);

        JButton confirmButton = new JButton("Confirm");
        confirmButton.setFont(FontManager.getRunescapeSmallFont());
        confirmButton.setBackground(ColorScheme.BRAND_ORANGE);
        confirmButton.setForeground(Color.WHITE);
        confirmButton.setFocusPainted(false);
        confirmButton.setToolTipText("Queue this exact order - it still goes through the same guardrail checks as any manual order");
        confirmButton.addActionListener(e -> onConfirmSuggestionClicked(decision));

        JButton dismissButton = new JButton("x");
        dismissButton.setFont(FontManager.getRunescapeSmallFont());
        dismissButton.setFocusPainted(false);
        dismissButton.setMargin(new Insets(0, 6, 0, 6));
        dismissButton.setToolTipText("Dismiss without queuing");
        dismissButton.addActionListener(e -> decisionSuggestions.remove(decision.getId()));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(confirmButton);
        buttonPanel.add(dismissButton);

        row.add(textPanel, BorderLayout.CENTER);
        row.add(buttonPanel, BorderLayout.EAST);
        return row;
    }

    /**
     * The ONLY path by which a model-proposed action can ever become a real order in this
     * plugin: converts the confirmed {@link PPOFlipperDecision} into a brand-new
     * {@link PPOFlipperOrder} and pushes it onto {@link #queue} via {@link OrderQueue#add} -
     * byte-for-byte the same call the add-order form and right-click dialogs make, so it is
     * indistinguishable from a manual order to {@link PPOFlipperStarScript}/{@link Guardrails}
     * from this point on. Runs the same {@link #validateNewOrder} pre-check the manual paths use
     * (a SELL for more than is held, or of something not held at all, is rejected here too rather
     * than silently queuing something Guardrails would reject anyway) before removing the
     * suggestion from the list.
     */
    private void onConfirmSuggestionClicked(PPOFlipperDecision decision) {
        if (!decision.isActionable() || decision.getGeAction() == null) {
            decisionSuggestions.remove(decision.getId());
            return;
        }

        String rejection = validateNewOrder(decision.getGeAction(), decision.getItemName(), decision.getQuantity());
        if (rejection != null) {
            JOptionPane.showMessageDialog(this, "Could not queue suggestion: " + rejection,
                "Suggestion rejected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        queue.add(new PPOFlipperOrder(decision.getGeAction(), decision.getItemId(), decision.getItemName(),
            decision.getQuantity(), decision.getPrice()));
        decisionSuggestions.remove(decision.getId());
    }

    // ---------------------------------------------------------------------------------------
    // Right-click integration entry points (PROPOSAL.md §2.1) - opens a small pre-filled dialog
    // rather than just populating the panel's own fields, since the panel may not be the
    // currently-visible sidebar tab when the right-click happens.
    // ---------------------------------------------------------------------------------------

    /** Opens a small modal dialog pre-filled for buying more of {@code item}, pushing directly onto the OrderQueue on confirm - same path the panel's own Add-to-queue button uses. */
    public void openBuyDialog(Rs2ItemModel item) {
        SwingUtilities.invokeLater(() -> showOrderDialog(GrandExchangeAction.BUY, item.getId(), item.getName(), 1));
    }

    /** Opens a small modal dialog pre-filled for selling {@code heldQuantity} of {@code item}, pushing directly onto the OrderQueue on confirm. */
    public void openSellDialog(Rs2ItemModel item, int heldQuantity) {
        SwingUtilities.invokeLater(() -> showOrderDialog(GrandExchangeAction.SELL, item.getId(), item.getName(), Math.max(1, heldQuantity)));
    }

    private void showOrderDialog(GrandExchangeAction action, int itemId, String itemName, int defaultQuantity) {
        Frame owner = JOptionPane.getFrameForComponent(this);
        JDialog dialog = new JDialog(owner, (action == GrandExchangeAction.BUY ? "Buy more: " : "Sell: ") + itemName, true);
        dialog.setLayout(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 8, 4, 8);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;

        JLabel title = new JLabel((action == GrandExchangeAction.BUY ? "Buy more " : "Sell ") + itemName);
        dialog.add(title, c);

        c.gridy++;
        c.gridwidth = 1;
        JLabel qtyLabel = new JLabel("Quantity");
        dialog.add(qtyLabel, c);
        c.gridx = 1;
        JSpinner qtySpinner = new JSpinner(new SpinnerNumberModel(Math.max(1, defaultQuantity), 1, 2_000_000_000, 1));
        dialog.add(qtySpinner, c);

        c.gridx = 0;
        c.gridy++;
        JLabel priceLabel = new JLabel("Price ea.");
        dialog.add(priceLabel, c);
        c.gridx = 1;
        JSpinner priceSpinnerDialog = new JSpinner(new SpinnerNumberModel(1, 1, 2_000_000_000, 1));
        dialog.add(priceSpinnerDialog, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(FAILED_RED);
        dialog.add(errorLabel, c);

        c.gridy++;
        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 8, 0));
        JButton confirm = new JButton(action == GrandExchangeAction.BUY ? "Queue buy" : "Queue sell");
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        confirm.addActionListener(e -> {
            int quantity = (Integer) qtySpinner.getValue();
            int price = (Integer) priceSpinnerDialog.getValue();
            String rejection = validateNewOrder(action, itemName, quantity);
            if (rejection != null) {
                errorLabel.setText(rejection);
                return;
            }
            queue.add(new PPOFlipperOrder(action, itemId, itemName, quantity, price));
            dialog.dispose();
        });
        buttonRow.add(confirm);
        buttonRow.add(cancel);
        dialog.add(buttonRow, c);

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
