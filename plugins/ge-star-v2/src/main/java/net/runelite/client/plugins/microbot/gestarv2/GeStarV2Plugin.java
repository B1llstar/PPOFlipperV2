package net.runelite.client.plugins.microbot.gestarv2;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
    name = "BotStar GE Star V2",
    description = "Buys and sells items on the Grand Exchange from name/price/quantity order lists, with spend and price guardrails.",
    tags = {"ge", "grand exchange", "flip", "buy", "sell", "economy"},
    authors = {"billstar"},
    version = GeStarV2Plugin.version,
    minClientVersion = "2.1.32",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class GeStarV2Plugin extends Plugin {

    static final String version = "2.0.0";

    @Inject
    private GeStarV2Config config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GeStarV2Overlay overlay;

    @Inject
    private GeStarV2Script script;

    @Provides
    GeStarV2Config provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GeStarV2Config.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
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
}
