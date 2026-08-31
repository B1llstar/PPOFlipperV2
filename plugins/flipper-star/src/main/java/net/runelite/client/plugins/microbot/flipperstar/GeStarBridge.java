package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reflective bridge to the running GE Star V2 plugin (a separately-built, separately-sideloaded
 * jar under plugins/ge-star-v2/). FlipperStar queues orders through GE Star V2 rather than
 * executing trades itself - GE Star V2 owns the click/widget/guardrail machinery, FlipperStar
 * owns picking what to flip.
 *
 * <p><b>Why reflection, not a compile-time dependency:</b> RuneLite gives every sideloaded
 * plugin jar its own {@link ClassLoader} (verified against the client jar's
 * {@code PluginClassLoader} bytecode - each one is constructed fresh per jar in
 * {@code PluginManager.loadSideLoadPlugins()}). If FlipperStar compiled against
 * {@code ge-star-v2}'s {@code GeStarOrder}/{@code GeStarOrderQueue} classes directly, its
 * shadow jar would bundle its own copies of them - a second, class-identity-incompatible
 * {@code GeStarOrderQueue.class} loaded by a different classloader, not the same Guice
 * singleton instance GE Star V2's own script and panel use. Orders added to it would silently
 * never reach the real queue.
 *
 * <p>The fix used throughout this codebase for this exact problem (see {@code geflipper} in
 * {@code vendor/microbot-hub/}, which reaches the third-party Flipping Copilot plugin the same
 * way): find the live plugin instance via {@link Microbot#getPluginManager()}, then call methods
 * on it reflectively. Every method called this way must only use types from the shared Microbot
 * client jar or {@code java.lang}/{@code java.util} (loaded once by the classloader every
 * plugin's own loader delegates to as parent, so identical across plugins) - never a
 * plugin-defined type like {@code GeStarOrder}. That's why
 * {@code GeStarOrderQueue.addOrder(GrandExchangeAction, String, int, int)} exists as a
 * primitives-only overload specifically for this bridge to call.
 */
@Slf4j
@Singleton
public class GeStarBridge {

    private static final String GE_STAR_PLUGIN_CLASS_NAME = "GeStarV2Plugin";

    // GE Star V2's ConfigGroup name/key for "Stop script when queue is empty" - see
    // GeStarV2Config.java. ConfigManager itself is a shared client-jar singleton (not a
    // plugin-defined type), so setting another plugin's config through it directly is safe
    // across the classloader boundary - unlike GeStarOrderQueue/GeStarPortfolio, no reflection
    // is needed here, just the right group/key string pair.
    private static final String GE_STAR_CONFIG_GROUP = "gestarv2";
    private static final String STOP_WHEN_ORDERS_COMPLETE_KEY = "stopWhenOrdersComplete";

    private final Gson gson = new Gson();
    private final ConfigManager configManager;

    @Inject
    public GeStarBridge(ConfigManager configManager) {
        this.configManager = configManager;
    }

    private Plugin geStarPlugin;
    private Object orderQueue;
    private Object portfolio;
    private Object script;

    /** Returns true if GE Star V2 is running and both its order queue and portfolio were reached. */
    public boolean isAvailable() {
        return findOrderQueue() != null && findPortfolio() != null;
    }

    /**
     * True if GE Star V2's execution script is actively ticking (i.e. Execute has been
     * clicked and it hasn't stopped) - via the shared Microbot {@code Script.isRunning()}
     * base-class method, safe to call reflectively since it returns a primitive and the
     * method itself is declared on a type loaded once by the shared parent classloader, not
     * a GE Star V2-defined type. False if GE Star V2 isn't reachable at all.
     */
    public boolean isScriptRunning() {
        Object scriptObj = findScript();
        if (scriptObj == null) return false;

        try {
            Method method = scriptObj.getClass().getMethod("isRunning");
            return (Boolean) method.invoke(scriptObj);
        } catch (Exception e) {
            log.error("FlipperStar: failed to read GE Star V2 script running state - {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Starts GE Star V2's execution script (equivalent of clicking Execute in its panel) if
     * it isn't already running. Returns true if the script is running after this call
     * (whether it was already running or just started), false if GE Star V2 isn't reachable.
     */
    public boolean startScriptIfNotRunning() {
        if (isScriptRunning()) return true;

        Plugin plugin = findGeStarPlugin();
        if (plugin == null) return false;

        try {
            Method method = plugin.getClass().getMethod("execute");
            method.invoke(plugin);
            log.info("FlipperStar: started GE Star V2's script (Automate)");
            return true;
        } catch (Exception e) {
            log.error("FlipperStar: failed to start GE Star V2's script - {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Turns off GE Star V2's "Stop script when queue is empty" setting, so its script stays
     * alive and keeps noticing newly-queued orders instead of shutting down once its queue
     * drains - a prerequisite for unattended operation (see GeStarV2Script's DONE-state
     * handling). Deliberately only ever turns this off, never back on - FlipperStar's own
     * Automate toggle shouldn't reach over and change GE Star V2's behavior back once turned
     * off, since the user may have set it deliberately for other reasons.
     */
    public void disableGeStarStopWhenOrdersComplete() {
        configManager.setConfiguration(GE_STAR_CONFIG_GROUP, STOP_WHEN_ORDERS_COMPLETE_KEY, false);
    }

    /**
     * Queues a buy/sell order into GE Star V2's live GeStarOrderQueue. Returns the new order's
     * id (a long, safe to cross the classloader boundary) or -1 if GE Star V2 isn't running or
     * the call failed for any reason.
     */
    public long addOrder(GrandExchangeAction action, String itemName, int quantity, int price) {
        Object queue = findOrderQueue();
        if (queue == null) {
            log.warn("FlipperStar: GE Star V2's order queue not reachable, cannot queue {} {}x {} @ {}",
                action, quantity, itemName, price);
            return -1;
        }

        try {
            Method addOrderMethod = queue.getClass().getMethod(
                "addOrder", GrandExchangeAction.class, String.class, int.class, int.class);
            Object result = addOrderMethod.invoke(queue, action, itemName, quantity, price);
            return (Long) result;
        } catch (Exception e) {
            log.error("FlipperStar: failed to queue order via GE Star V2's addOrder - {}", e.getMessage(), e);
            return -1;
        }
    }

    /** Total quantity of an item currently held across bank + inventory, via GE Star V2's GeStarPortfolio. 0 if unreachable. */
    public int getHeldQuantity(String itemName) {
        Object portfolioObj = findPortfolio();
        if (portfolioObj == null) return 0;

        try {
            Method method = portfolioObj.getClass().getMethod("getHeldQuantity", String.class);
            return (Integer) method.invoke(portfolioObj, itemName);
        } catch (Exception e) {
            log.error("FlipperStar: failed to read held quantity for {} - {}", itemName, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Status name (QUEUED/SUBMITTED/DONE/SKIPPED/FAILED) of an order previously queued via
     * {@link #addOrder}, or null if it's gone from GE Star V2's queue (e.g. removed).
     */
    public String getOrderStatusName(long orderId) {
        Object queue = findOrderQueue();
        if (queue == null) return null;

        try {
            Method method = queue.getClass().getMethod("getOrderStatusName", long.class);
            return (String) method.invoke(queue, orderId);
        } catch (Exception e) {
            log.error("FlipperStar: failed to read order status for id {} - {}", orderId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Every open position in GE Star V2's portfolio (item id/name, quantity held, average
     * cost, purchase timestamp), via GeStarPortfolio.getOpenPositionsJson(). Empty list if
     * unreachable or there are no open positions.
     */
    public List<OpenPosition> getOpenPositions() {
        Object portfolioObj = findPortfolio();
        if (portfolioObj == null) return Collections.emptyList();

        try {
            Method method = portfolioObj.getClass().getMethod("getOpenPositionsJson");
            String json = (String) method.invoke(portfolioObj);
            OpenPosition[] parsed = gson.fromJson(json, OpenPosition[].class);
            return parsed != null ? Arrays.asList(parsed) : Collections.emptyList();
        } catch (Exception e) {
            log.error("FlipperStar: failed to read open positions - {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** Weighted-average cost per unit for an item id, via GE Star V2's GeStarPortfolio. 0 if unreachable or never bought. */
    public int getAverageCost(int itemId) {
        Object portfolioObj = findPortfolio();
        if (portfolioObj == null) return 0;

        try {
            Method method = portfolioObj.getClass().getMethod("getAverageCost", int.class);
            return (Integer) method.invoke(portfolioObj, itemId);
        } catch (Exception e) {
            log.error("FlipperStar: failed to read average cost for item {} - {}", itemId, e.getMessage(), e);
            return 0;
        }
    }

    private Plugin findGeStarPlugin() {
        if (geStarPlugin == null) {
            geStarPlugin = Microbot.getPluginManager()
                .getPlugins()
                .stream()
                .filter(plugin -> plugin.getClass().getSimpleName().equals(GE_STAR_PLUGIN_CLASS_NAME))
                .findFirst()
                .orElse(null);
        }
        return geStarPlugin;
    }

    private Object findOrderQueue() {
        if (orderQueue != null) return orderQueue;

        Plugin plugin = findGeStarPlugin();
        if (plugin == null) return null;

        orderQueue = getFieldValue(plugin, "queue");
        return orderQueue;
    }

    private Object findPortfolio() {
        if (portfolio != null) return portfolio;

        Plugin plugin = findGeStarPlugin();
        if (plugin == null) return null;

        portfolio = getFieldValue(plugin, "portfolio");
        return portfolio;
    }

    private Object findScript() {
        if (script != null) return script;

        Plugin plugin = findGeStarPlugin();
        if (plugin == null) return null;

        script = getFieldValue(plugin, "script");
        return script;
    }

    private Object getFieldValue(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            log.error("FlipperStar: could not access GE Star V2's {} field - {}", fieldName, e.getMessage(), e);
            return null;
        }
    }

    /** Clears cached references - call if GE Star V2 might have been restarted, so stale instances aren't reused. */
    public void reset() {
        geStarPlugin = null;
        orderQueue = null;
        portfolio = null;
        script = null;
    }
}
