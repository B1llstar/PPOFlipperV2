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

    private static final String CONFIG_GROUP = "flipperstar";

    @Inject
    private FlipperStarConfig config;

    @Inject
    private FlipperStarEngine engine;

    @Inject
    private GeStarBridge geStarBridge;

    @Inject
    private ConfigManager configManager;

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

        if (config.automateEnabled()) {
            applyAutomate();
        } else if (config.autoScanEnabled()) {
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
        if (!event.getGroup().equals(CONFIG_GROUP)) return;

        if (event.getKey().equals("automateEnabled")) {
            if (config.automateEnabled()) {
                applyAutomate();
            } else {
                // Only stops FlipperStar's own scanning - deliberately does not touch GE Star
                // V2's running state or its "Stop script when queue is empty" setting, so
                // toggling Automate off doesn't yank anything out from under an in-progress
                // trade. See FlipperStarConfig's automateEnabled description.
                engine.stopAutoScan();
            }
            if (panel != null) panel.refresh();
            return;
        }

        if (!event.getKey().equals("autoScanEnabled") && !event.getKey().equals("autoScanIntervalMinutes")) return;

        if (config.autoScanEnabled()) {
            engine.startAutoScan(config);
        } else {
            engine.stopAutoScan();
        }
        if (panel != null) panel.refresh();
    }

    /**
     * Turns Automate into its individual effects: makes sure GE Star V2's script is running
     * and won't stop itself once its queue drains, turns on both auto-scan and exit-scan in
     * config (which - via the autoScanEnabled/exitScanEnabled ConfigChanged handling above and
     * FlipperStarEngine.scanAndQueue's internal gating - actually starts continuous buy+sell
     * automation), and starts the auto-scan timer directly here too, since setConfiguration
     * calls below may not always fire a ConfigChanged event synchronously before this method
     * returns.
     */
    private void applyAutomate() {
        geStarBridge.startScriptIfNotRunning();
        geStarBridge.disableGeStarStopWhenOrdersComplete();

        configManager.setConfiguration(CONFIG_GROUP, "autoScanEnabled", true);
        configManager.setConfiguration(CONFIG_GROUP, "exitScanEnabled", true);

        engine.startAutoScan(config);
        log.info("FlipperStar: Automate enabled - GE Star V2 running, auto-scan and exit-scan on");
    }
}
