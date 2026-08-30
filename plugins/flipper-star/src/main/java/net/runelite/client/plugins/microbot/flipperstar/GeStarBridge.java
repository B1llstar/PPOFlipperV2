package net.runelite.client.plugins.microbot.flipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private Plugin geStarPlugin;
    private Object orderQueue;
    private Object portfolio;

    /** Returns true if GE Star V2 is running and both its order queue and portfolio were reached. */
    public boolean isAvailable() {
        return findOrderQueue() != null && findPortfolio() != null;
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
    }
}
