package net.runelite.client.plugins.microbot.afkwoodcutting;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
    name = "BotStar Afk Woodcutting",
    description = "Chops the configured tree and banks or drops logs when full.",
    tags = {"woodcutting", "afk", "skilling"},
    authors = {"billstar"},
    version = AfkWoodcuttingPlugin.version,
    minClientVersion = "1.9.6",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class AfkWoodcuttingPlugin extends Plugin {

    static final String version = "1.0.0";

    @Inject
    private AfkWoodcuttingConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AfkWoodcuttingOverlay overlay;

    @Inject
    private AfkWoodcuttingScript script;

    @Provides
    AfkWoodcuttingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AfkWoodcuttingConfig.class);
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
}
