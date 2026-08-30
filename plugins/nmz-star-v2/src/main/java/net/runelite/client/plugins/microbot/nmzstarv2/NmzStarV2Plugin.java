package net.runelite.client.plugins.microbot.nmzstarv2;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
    name = "BotStar Nmz Star V2",
    description = "Skeleton Nightmare Zone combat bot: enters the dream, finds the rotated-in boss, attacks it.",
    tags = {"nmz", "nightmare zone", "combat", "minigame"},
    authors = {"billstar"},
    version = NmzStarV2Plugin.version,
    minClientVersion = "1.9.6",
    enabledByDefault = false,
    isExternal = true
)
@Slf4j
public class NmzStarV2Plugin extends Plugin {

    static final String version = "0.1.0";

    @Inject
    private NmzStarV2Config config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private NmzStarV2Overlay overlay;

    @Inject
    private NmzStarV2Script script;

    @Provides
    NmzStarV2Config provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NmzStarV2Config.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        script.run();
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (config.stopAfterDeath() && actorDeath.getActor() == Microbot.getClient().getLocalPlayer()) {
            Microbot.stopPlugin(this);
        }
    }
}
