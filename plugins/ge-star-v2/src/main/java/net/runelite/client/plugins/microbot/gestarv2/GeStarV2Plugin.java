package net.runelite.client.plugins.microbot.gestarv2;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.GeStarPortfolio;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@PluginDescriptor(
    name = "BotStar GE Star V2",
    description = "Buys and sells items on the Grand Exchange from an order queue managed in the sidebar panel, with spend and price guardrails.",
    tags = {"ge", "grand exchange", "flip", "buy", "sell", "economy"},
    authors = {"billstar"},
    version = GeStarV2Plugin.version,
    minClientVersion = "2.1.32",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class GeStarV2Plugin extends Plugin {

    static final String version = "2.6.0";

    @Inject
    private GeStarV2Config config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GeStarV2Overlay overlay;

    @Inject
    private GeStarV2Script script;

    @Inject
    private GeStarOrderQueue queue;

    @Inject
    private GeStarPortfolio portfolio;

    @Inject
    private BuyLimitLedger buyLimitLedger;

    @Inject
    private GeStarFirestoreSync firestoreSync;

    @Inject
    private ClientToolbar clientToolbar;

    private GeStarV2Panel panel;
    private NavigationButton navButton;

    @Provides
    GeStarV2Config provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GeStarV2Config.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        addPanel();
        // The script only starts when the panel's Execute button is clicked - enabling the
        // plugin just makes the sidebar panel and overlay available.

        if (config.firestoreSyncEnabled()) {
            firestoreSync.start(config);
        }
    }

    @Override
    protected void shutDown() {
        firestoreSync.stop();
        script.shutdown();
        removePanel();
        overlayManager.remove(overlay);
    }

    private void addPanel() {
        panel = new GeStarV2Panel(this, script, queue, portfolio, buyLimitLedger);

        // Reuse the client's own Grand Exchange icon (bundled in the client jar) instead of
        // shipping a duplicate image asset.
        BufferedImage icon = ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png");

        navButton = NavigationButton.builder()
            .tooltip("GE Star V2")
            .icon(icon)
            .priority(6)
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

    /**
     * Real-time fill detection: the client fires this whenever any GE offer's state or
     * quantity-sold/bought changes, which is how we notice a buy/sell completed (or
     * partially filled) without polling widgets on every tick.
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        script.onOfferChanged(event);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("gestarv2")) return;
        if (!event.getKey().equals("firestoreSyncEnabled") && !event.getKey().equals("firestoreServiceAccountPath")) return;

        if (config.firestoreSyncEnabled()) {
            firestoreSync.start(config);
        } else {
            firestoreSync.stop();
        }
    }
}
