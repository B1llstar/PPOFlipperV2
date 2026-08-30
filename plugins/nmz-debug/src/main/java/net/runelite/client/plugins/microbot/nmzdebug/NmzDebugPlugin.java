package net.runelite.client.plugins.microbot.nmzdebug;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = "BotStar Nmz DEBUG",
        description = "Instrumented copy of Microbot-Hub's Nmz plugin with [NMZDEBUG] console logging at every branch, to find why the original stalls in Idle.",
        authors = { "billstar" },
        version = NmzDebugPlugin.version,
        minClientVersion = "2.1.0",
        tags = {"nmz", "debug"},
        enabledByDefault = true,
        isExternal = true
)
@Slf4j
public class NmzDebugPlugin extends Plugin {
    final static String version = "2.4.1";
    @Inject
    private NmzDebugConfig config;

    @Provides
    NmzDebugConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NmzDebugConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private NmzDebugOverlay nmzOverlay;

    @Inject
    NmzDebugScript nmzScript;
    @Inject
    PrayerPotionScript prayerPotionScript;

    @Override
    protected void startUp() throws AWTException {
        System.out.println("[NMZDEBUG] NmzDebugPlugin.startUp() called");
        if (overlayManager != null) {
            overlayManager.add(nmzOverlay);
        }
        nmzScript.run();
        if (config.togglePrayerPotions()) {
            prayerPotionScript.run(config);
        }
    }

    protected void shutDown() {
        System.out.println("[NMZDEBUG] NmzDebugPlugin.shutDown() called");
        nmzScript.shutdown();
        overlayManager.remove(nmzOverlay);
        NmzDebugScript.setHasSurge(false);
    }

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (config.stopAfterDeath() && actorDeath.getActor() == Microbot.getClient().getLocalPlayer()) {
            System.out.println("[NMZDEBUG] onActorDeath: player died, stopAfterDeath=true, will stop plugin once outside");
            Microbot.getClientThread().runOnSeperateThread(() -> {
                Global.sleepUntil(nmzScript::isOutside, 10000);
                Microbot.stopPlugin(this);
                return true;
            });
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() == ChatMessageType.GAMEMESSAGE) {
            if (event.getMessage().equalsIgnoreCase("you feel a surge of special attack power!")) {
                NmzDebugScript.setHasSurge(true);
            } else if (event.getMessage().equalsIgnoreCase("your surge of special attack power has ended.")) {
                NmzDebugScript.setHasSurge(false);
            }
        }
    }
}
