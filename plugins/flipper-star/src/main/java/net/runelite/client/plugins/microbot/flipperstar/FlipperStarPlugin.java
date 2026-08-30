package net.runelite.client.plugins.microbot.flipperstar;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@PluginDescriptor(
    name = "BotStar FlipperStar",
    description = "Scans the Grand Exchange via a local scoring model and queues promising flips into GE Star V2.",
    tags = {"ge", "grand exchange", "flip", "flipper", "ai", "ml"},
    authors = {"billstar"},
    version = FlipperStarPlugin.version,
    minClientVersion = "2.1.32",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class FlipperStarPlugin extends Plugin {

    static final String version = "1.0.0";

    @Inject
    private FlipperStarConfig config;

    @Inject
    private FlipperStarEngine engine;

    @Inject
    private GeStarBridge geStarBridge;

    @Inject
    private ClientToolbar clientToolbar;

    private FlipperStarPanel panel;
    private NavigationButton navButton;

    @Provides
    FlipperStarConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(FlipperStarConfig.class);
    }

    @Override
    protected void startUp() {
        geStarBridge.reset();
        addPanel();

        if (config.autoScanEnabled()) {
            engine.startAutoScan(config);
        }
    }

    @Override
    protected void shutDown() {
        engine.stopAutoScan();
        removePanel();
    }

    private void addPanel() {
        panel = new FlipperStarPanel(this, engine, config, geStarBridge);

        // Reuse the client's own Grand Exchange icon (bundled in the client jar), same as
        // GE Star V2 - these two plugins are visually paired in the sidebar.
        BufferedImage icon = ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png");

        navButton = NavigationButton.builder()
            .tooltip("FlipperStar")
            .icon(icon)
            .priority(5)
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

    public void scanNow() {
        engine.scanAndQueue(config);
        if (panel != null) panel.refresh();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("flipperstar")) return;
        if (!event.getKey().equals("autoScanEnabled") && !event.getKey().equals("autoScanIntervalMinutes")) return;

        if (config.autoScanEnabled()) {
            engine.startAutoScan(config);
        } else {
            engine.stopAutoScan();
        }
        if (panel != null) panel.refresh();
    }
}
