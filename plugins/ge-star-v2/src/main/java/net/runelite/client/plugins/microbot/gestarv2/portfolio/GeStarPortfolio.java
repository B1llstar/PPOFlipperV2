package net.runelite.client.plugins.microbot.gestarv2.portfolio;

import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tracks what's actually held (bank + inventory, live from the client) and the cost basis
 * built up from completed GE trades (persisted, since the client doesn't track "what did I
 * pay for this" anywhere itself). Shared by GE Star V2's guardrails (e.g. don't oversell what
 * you own) and the GE Flipper plugin (needs both current holdings and cost basis to size new
 * flips and compute realized P&L).
 *
 * Holdings are read live on every call - they're cheap client-side lookups, no reason to
 * cache them stale. The cost-basis ledger is the only state actually owned by this class, and
 * it's persisted through ConfigManager so it survives plugin/client restarts.
 */
@Slf4j
@Singleton
public class GeStarPortfolio {

    private static final String CONFIG_GROUP = "gestarv2";
    private static final String LEDGER_KEY = "portfolioCostBasisLedger";
    private static final Type LEDGER_TYPE = new TypeToken<Map<Integer, CostBasisEntry>>() {}.getType();

    private final ConfigManager configManager;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();

    private final Map<Integer, CostBasisEntry> ledger;

    @Inject
    public GeStarPortfolio(ConfigManager configManager) {
        this.configManager = configManager;
        this.ledger = loadLedger();
    }

    private Map<Integer, CostBasisEntry> loadLedger() {
        Map<Integer, CostBasisEntry> loaded = configManager.getConfiguration(CONFIG_GROUP, LEDGER_KEY, LEDGER_TYPE);
        return loaded != null ? new HashMap<>(loaded) : new HashMap<>();
    }

    private void persistLedger() {
        configManager.setConfiguration(CONFIG_GROUP, LEDGER_KEY, ledger);
    }

    /** Total quantity of an item held across bank + inventory right now. */
    public int getHeldQuantity(int itemId) {
        return Rs2Inventory.all(i -> i.getId() == itemId).stream().mapToInt(Rs2ItemModel::getQuantity).sum()
            + Rs2Bank.bankItems().stream().filter(i -> i.getId() == itemId).mapToInt(Rs2ItemModel::getQuantity).sum();
    }

    public int getHeldQuantity(String itemName) {
        int itemId = itemManager.getItemId(itemName);
        return itemId > 0 ? getHeldQuantity(itemId) : 0;
    }

    /** Snapshot of every item currently held (bank + inventory combined), keyed by item id. */
    public Map<Integer, Integer> getAllHoldings() {
        Map<Integer, Integer> holdings = new HashMap<>();
        Stream.concat(Rs2Inventory.all().stream(), Rs2Bank.bankItems().stream())
            .forEach(item -> holdings.merge(item.getId(), item.getQuantity(), Integer::sum));
        return holdings;
    }

    /** Weighted-average cost per unit for an item, from the persisted ledger. 0 if never bought (or fully sold back out) through this ledger. */
    public int getAverageCost(int itemId) {
        CostBasisEntry entry = ledger.get(itemId);
        return entry != null ? entry.getAverageCost() : 0;
    }

    /** Records a completed buy fill against the cost-basis ledger and persists it. Call this once per completed BUY offer, with the actual quantity/gp spent (not the requested order size - a partial fill only cost-bases what actually filled). */
    public synchronized void recordBuy(int itemId, int quantity, long totalSpent) {
        if (quantity <= 0) return;
        ledger.computeIfAbsent(itemId, CostBasisEntry::new).recordBuy(quantity, totalSpent);
        persistLedger();
    }

    /** Records a completed sell fill against the cost-basis ledger and persists it, realizing profit/loss on the sold portion. */
    public synchronized void recordSell(int itemId, int quantity, long totalReceived) {
        if (quantity <= 0) return;
        ledger.computeIfAbsent(itemId, CostBasisEntry::new).recordSell(quantity, totalReceived);
        persistLedger();
    }

    public long getRealizedProfit(int itemId) {
        CostBasisEntry entry = ledger.get(itemId);
        return entry != null ? entry.getRealizedProfit() : 0;
    }

    /** Total realized profit/loss across every item ever traded through this ledger. */
    public long getTotalRealizedProfit() {
        return ledger.values().stream().mapToLong(CostBasisEntry::getRealizedProfit).sum();
    }

    /** Every item with a nonzero tracked position, for portfolio overviews. */
    public List<CostBasisEntry> getOpenPositions() {
        return ledger.values().stream()
            .filter(e -> e.getQuantityHeld() > 0)
            .collect(Collectors.toList());
    }
}
