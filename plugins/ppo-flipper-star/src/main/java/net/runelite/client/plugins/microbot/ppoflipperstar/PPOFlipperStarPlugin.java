package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.WidgetIndices;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@PluginDescriptor(
    name = "BotStar PPOFlipperStar",
    description = "Self-contained Grand Exchange flipping mechanics (order queue, portfolio, guardrails) with manual controls now, a PPO policy in a later milestone.",
    tags = {"ge", "grand exchange", "flip", "flipper", "ai", "ml", "ppo"},
    authors = {"billstar"},
    version = PPOFlipperStarPlugin.version,
    minClientVersion = "2.1.32",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class PPOFlipperStarPlugin extends Plugin {

    static final String version = "1.0.0";

    @Inject
    private PPOFlipperStarConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private PPOFlipperStarOverlay overlay;

    @Inject
    private PPOFlipperStarScript script;

    @Inject
    private OrderQueue queue;

    @Inject
    private PortfolioManager portfolio;

    @Inject
    private BuyLimitLedger buyLimitLedger;

    @Inject
    private WatchlistManager watchlistManager;

    @Inject
    private GoldManager goldManager;

    @Inject
    private ClientToolbar clientToolbar;

    private PPOFlipperStarPanel panel;
    private NavigationButton navButton;

    @Provides
    PPOFlipperStarConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PPOFlipperStarConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        addPanel();
        // The script only starts when the panel's Execute button is clicked - enabling the
        // plugin just makes the sidebar panel and overlay available, same lifecycle as
        // ge-star-v2.
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        removePanel();
        overlayManager.remove(overlay);
    }

    private void addPanel() {
        panel = new PPOFlipperStarPanel(this, script, queue, portfolio, goldManager, watchlistManager);

        // Reuse the client's own Grand Exchange icon (bundled in the client jar) instead of
        // shipping a duplicate image asset.
        BufferedImage icon = ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png");

        navButton = NavigationButton.builder()
            .tooltip("PPOFlipperStar")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);
    }

    private void removePanel() {
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        panel = null;
    }

    public void execute() {
        script.run(config);
        if (panel != null) panel.onScriptStateChanged();
    }

    public void stop() {
        script.shutdown();
        if (panel != null) panel.onScriptStateChanged();
    }

    /** Aborts every currently active GE offer and collects whatever comes back to inventory/bank, marking affected orders FAILED. */
    public void cancelAllOffers() {
        script.requestCancelAll(config);
        if (panel != null) panel.onScriptStateChanged();
    }

    /**
     * Real-time fill detection: the client fires this whenever any GE offer's state or
     * quantity-sold/bought changes, which is how we notice a buy/sell completed (or partially
     * filled) without polling widgets on every tick.
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        script.onOfferChanged(event);
    }

    /**
     * Right-click inventory integration (PROPOSAL.md §2.1): adds "Buy more", "Sell", and a
     * Watch/Unwatch toggle to any inventory-slot menu entry, opening the panel's pre-filled
     * add-order dialog or toggling watchlist membership. Follows the addMenuEntry construction
     * pattern from vendor/microbot-hub's QoLPlugin (creates a new client-side MenuEntry via
     * Client.createMenuEntry at the next free index, copying the triggering entry's
     * param0/param1/identifier/type so it targets the same inventory slot).
     */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (event.getMenuEntry().getParam1() != WidgetIndices.ResizableModernViewport.INVENTORY_CONTAINER) return;

        Rs2ItemModel item = Rs2Inventory.getItemInSlot(event.getMenuEntry().getParam0());
        if (item == null) return;

        addMenuEntry(event, "Buy more", item.getName(), e -> {
            if (panel != null) panel.openBuyDialog(item);
        });
        addMenuEntry(event, "Sell", item.getName(), e -> {
            if (panel != null) panel.openSellDialog(item, portfolio.getHeldQuantity(item.getId()));
        });

        boolean watched = watchlistManager.contains(item.getId());
        addMenuEntry(event, watched ? "Unwatch" : "Watch", item.getName(), e -> {
            if (watched) {
                watchlistManager.remove(item.getId());
            } else {
                watchlistManager.add(item.getId());
            }
        });
    }

    private void addMenuEntry(MenuEntryAdded event, String option, String target, java.util.function.Consumer<MenuEntry> callback) {
        int index = Microbot.getClient().getMenuEntries().length;
        Microbot.getClient().createMenuEntry(index)
            .setOption(option)
            .setTarget(target)
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(event.getMenuEntry().getType())
            .onClick(callback);
    }
}
