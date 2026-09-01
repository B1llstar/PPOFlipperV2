package net.runelite.client.plugins.microbot.gestarv2.portfolio;

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
import java.util.UUID;

/**
 * Tracks actual buy fills per item, timestamped, so a rolling-4h-window buy limit (the GE's own
 * per-item cap) can be enforced across scans and across plugin/client restarts - not just within
 * a single order. Deliberately a per-fill event log rather than an aggregate/blended value like
 * {@link CostBasisEntry}'s weighted acquisition timestamp: an aggregate can't answer "how much
 * was bought in just the last 4 hours" once a position spans buys from outside that window.
 *
 * <p>Persisted the same hand-rolled-JSON-via-Gson way as {@link GeStarPortfolio}'s cost-basis
 * ledger, for the same reason (see that class's javadoc) - {@code ConfigManager}'s generic
 * {@code Object} overload doesn't produce valid JSON for a plain {@code Map}.
 */
@Slf4j
@Singleton
public class BuyLimitLedger {

    private static final String CONFIG_GROUP = "gestarv2";
    private static final String LEDGER_KEY = "buyLimitLedger";
    private static final String AGENT_ID_KEY = "buyLimitAgentId";
    private static final Type LEDGER_TYPE = new TypeToken<Map<Integer, List<PurchaseEvent>>>() {}.getType();

    /** OSRS GE buy limits reset on a rolling window from each individual purchase, 4 hours wide. */
    public static final long WINDOW_MILLIS = 4L * 60 * 60 * 1000;

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    private final Map<Integer, List<PurchaseEvent>> events;
    private final String agentId;

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
        this.agentId = loadOrCreateAgentId();
    }

    /** Stable per-installation id, generated once and persisted locally - identifies this agent's buy history when mirrored to Firestore. Not tied to any web-UI account/uid. */
    public String getAgentId() {
        return agentId;
    }

    private String loadOrCreateAgentId() {
        String existing = configManager.getConfiguration(CONFIG_GROUP, AGENT_ID_KEY);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing.trim();
        }
        String generated = UUID.randomUUID().toString();
        configManager.setConfiguration(CONFIG_GROUP, AGENT_ID_KEY, generated);
        return generated;
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
            log.warn("GE Star V2: buy-limit ledger config was not valid JSON, resetting it - {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void persistEvents() {
        configManager.setConfiguration(CONFIG_GROUP, LEDGER_KEY, gson.toJson(events, LEDGER_TYPE));
    }

    /** Records a completed buy fill, timestamped now (or at the given time) - call once per completed BUY offer with the actual filled quantity. */
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
