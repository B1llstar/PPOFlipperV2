package net.runelite.client.plugins.microbot.ppoflipperstar.portfolio;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks actual buy fills per item, timestamped, so a rolling-4h-window buy limit (the GE's own
 * per-item cap) can be enforced across sessions and across plugin/client restarts - not just
 * within a single order. Deliberately a per-fill event log rather than an aggregate/blended
 * value like {@link CostBasisEntry}'s weighted acquisition timestamp: an aggregate can't answer
 * "how much was bought in just the last 4 hours" once a position spans buys from outside that
 * window.
 *
 * <p><b>Persistence:</b> hand-rolled JSON via Gson, stored/loaded through
 * {@link ConfigManager}'s plain {@code String} overloads rather than its generic
 * {@code setConfiguration(group, key, Object)} overload. That generic overload only cleanly
 * serializes a handful of special-cased types (Color, Enum, Set, a few others) - for a plain
 * {@code Map} (or any other custom object) it silently falls back to {@code Object.toString()},
 * producing Java's default {@code {key=value}} debug format rather than valid JSON, which then
 * fails to parse back on the next load. See {@code GeStarPortfolio}'s javadoc in the sibling
 * ge-star-v2 plugin for the full story of the bug this avoids - this class reimplements the
 * same safe pattern independently, with no code shared or imported between the two plugins.
 */
@Slf4j
@Singleton
public class BuyLimitLedger {

    private static final String CONFIG_GROUP = "ppoflipperstar";
    private static final String LEDGER_KEY = "buyLimitLedger";
    private static final Type LEDGER_TYPE = new TypeToken<Map<Integer, List<PurchaseEvent>>>() {}.getType();

    /** OSRS GE buy limits reset on a rolling window from each individual purchase, 4 hours wide. */
    public static final long WINDOW_MILLIS = 4L * 60 * 60 * 1000;

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    private final Map<Integer, List<PurchaseEvent>> events;

    public static final class PurchaseEvent {
        public final int quantity;
        public final long timestampMillis;

        public PurchaseEvent(int quantity, long timestampMillis) {
            this.quantity = quantity;
            this.timestampMillis = timestampMillis;
        }
    }

    @Inject
    public BuyLimitLedger(ConfigManager configManager) {
        this.configManager = configManager;
        this.events = loadEvents();
    }

    private Map<Integer, List<PurchaseEvent>> loadEvents() {
        String json = configManager.getConfiguration(CONFIG_GROUP, LEDGER_KEY);
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<Integer, List<PurchaseEvent>> loaded = gson.fromJson(json, LEDGER_TYPE);
            return loaded != null ? new HashMap<>(loaded) : new HashMap<>();
        } catch (JsonSyntaxException e) {
            log.warn("PPOFlipperStar: buy-limit ledger config was not valid JSON, resetting it - {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void persistEvents() {
        configManager.setConfiguration(CONFIG_GROUP, LEDGER_KEY, gson.toJson(events, LEDGER_TYPE));
    }

    /** Records a completed buy fill, timestamped - call once per completed BUY offer with the actual filled quantity. */
    public synchronized void recordBuy(int itemId, int quantity, long timestampMillis) {
        if (quantity <= 0) return;
        List<PurchaseEvent> itemEvents = events.computeIfAbsent(itemId, k -> new ArrayList<>());
        itemEvents.add(new PurchaseEvent(quantity, timestampMillis));
        pruneExpired(itemEvents, timestampMillis);
        persistEvents();
    }

    /** Total quantity of an item bought within the trailing {@link #WINDOW_MILLIS} of nowMillis - what still counts against its GE buy limit right now. Prunes expired events as a side effect so the ledger doesn't grow unbounded. */
    public synchronized int quantityBoughtInWindow(int itemId, long nowMillis) {
        List<PurchaseEvent> itemEvents = events.get(itemId);
        if (itemEvents == null || itemEvents.isEmpty()) return 0;

        pruneExpired(itemEvents, nowMillis);
        if (itemEvents.isEmpty()) {
            events.remove(itemId);
            persistEvents();
            return 0;
        }

        int total = 0;
        for (PurchaseEvent event : itemEvents) {
            total += event.quantity;
        }
        return total;
    }

    private static void pruneExpired(List<PurchaseEvent> itemEvents, long nowMillis) {
        itemEvents.removeIf(e -> nowMillis - e.timestampMillis >= WINDOW_MILLIS);
    }
}
