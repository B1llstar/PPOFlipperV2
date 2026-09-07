package net.runelite.client.plugins.microbot.nmzdebug;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
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
    final static String version = "2.5.0";
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
    @Inject
    NmzDisconnectLog disconnectLog;

    // Set once LOGGED_IN has been observed, so a later LOGIN_SCREEN can be told apart from
    // startup/manual-logout noise and treated as an actual disconnect.
    private boolean wasLoggedIn = false;

    @Override
    protected void startUp() throws AWTException {
        NmzDebugLog.init(disconnectLog);
        NmzDebugLog.log("[NMZDEBUG] NmzDebugPlugin.startUp() called");
        wasLoggedIn = Microbot.isLoggedIn();
        if (overlayManager != null) {
            overlayManager.add(nmzOverlay);
        }
        nmzScript.run();
        if (config.togglePrayerPotions()) {
            prayerPotionScript.run(config);
        }
    }

    protected void shutDown() {
        NmzDebugLog.log("[NMZDEBUG] NmzDebugPlugin.shutDown() called");
        nmzScript.shutdown();
        overlayManager.remove(nmzOverlay);
        NmzDebugScript.setHasSurge(false);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.CONNECTION_LOST) {
            NmzDebugLog.log("[NMZDEBUG] onGameStateChanged: CONNECTION_LOST - dumping disconnect log");
            disconnectLog.dumpToDisk("CONNECTION_LOST");
            reconnect("CONNECTION_LOST");
        } else if (state == GameState.LOGIN_SCREEN && wasLoggedIn) {
            NmzDebugLog.log("[NMZDEBUG] onGameStateChanged: LOGIN_SCREEN after being logged in - dumping disconnect log");
            disconnectLog.dumpToDisk("LOGIN_SCREEN (dropped from LOGGED_IN)");
            reconnect("LOGIN_SCREEN (dropped from LOGGED_IN)");
        }
        if (state == GameState.LOGGED_IN) {
            wasLoggedIn = true;
        } else if (state == GameState.LOGIN_SCREEN) {
            wasLoggedIn = false;
        }
    }

    /**
     * Reacts to a disconnect by dismissing whatever prompt/dialog is currently on screen and
     * clicking back in via the active profile's saved login (the "Existing user" button showing
     * the profile's own username) - {@link LoginManager#login(int)} does exactly this in one call
     * (confirmed via bytecode: it runs {@code handleDisconnectDialogs} first, which dismisses a
     * stuck disconnect dialog by index, then sets the world/credentials and submits), reading
     * whatever profile {@link LoginManager#getActiveProfile()} already resolves to - the same
     * profile this plugin already trusts for bank-pin decryption in
     * {@link NmzDebugScript#handleStore}, so this needs no separate credential source of its own.
     *
     * <p><b>Explicitly forces a members world via {@link LoginManager#getRandomWorld(boolean)}
     * (true) rather than the plain no-arg {@link LoginManager#login()}.</b> Real incident: a
     * disconnect that happened while genuinely inside the (members-only) Nightmare Zone instance
     * reconnected onto a free-to-play world instead, hitting a blocking "To access this free
     * world, log into a members world" dialog with a "Try again" button - one this reconnect logic
     * had no handling for at all, so the account was stuck at the login screen indefinitely rather
     * than actually getting back in. The no-arg {@code login()} resolves its world from the active
     * profile's own {@code selectedWorld}/membership flag (per its own bytecode), which is
     * apparently not reliably set to a members world for this profile - NMZ specifically can never
     * be played on a free world, so this reconnect path has no reason to ever risk that ambiguity;
     * it should always explicitly demand a members world regardless of what the profile itself
     * happens to have configured.
     *
     * <p>Dispatched via {@code runOnSeperateThread}, matching {@link #onActorDeath}'s own pattern
     * in this class - {@code login()} clicks widgets and sleeps between steps internally (per its
     * bytecode), which must never run directly on the event-bus callback's thread (effectively the
     * client thread) the way {@code onGameStateChanged} itself is invoked on.
     *
     * <p>Deliberately fire-and-forget beyond a single attempt: {@link LoginManager#login(int)} is
     * already self-throttling (a 1500ms minimum gap between attempts, tracked internally) and a
     * no-op while a login attempt is already active, so calling it once per disconnect event here
     * is enough - if this attempt doesn't land (e.g. the world is full, a "world 302 is currently
     * full" dialog needing a different response), the very next tick's {@code CONNECTION_LOST}/
     * {@code LOGIN_SCREEN} transition (RuneLite keeps firing these while disconnected) triggers
     * another attempt (picking a fresh random members world each time) rather than this needing
     * its own retry loop.
     */
    private void reconnect(String reason) {
        NmzDebugLog.log("[NMZDEBUG] reconnect: disconnect detected (" + reason + ") - dismissing prompt and logging back in via LoginManager.login() on a members world");
        Microbot.getClientThread().runOnSeperateThread(() -> {
            int membersWorld = LoginManager.getRandomWorld(true);
            boolean success = LoginManager.login(membersWorld);
            NmzDebugLog.log("[NMZDEBUG] reconnect: LoginManager.login(" + membersWorld + ") -> " + success);
            return true;
        });
    }

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (config.stopAfterDeath() && actorDeath.getActor() == Microbot.getClient().getLocalPlayer()) {
            NmzDebugLog.log("[NMZDEBUG] onActorDeath: player died, stopAfterDeath=true, will stop plugin once outside");
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
