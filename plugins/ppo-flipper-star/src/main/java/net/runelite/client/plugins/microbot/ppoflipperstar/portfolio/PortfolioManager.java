package net.runelite.client.plugins.microbot.ppoflipperstar.portfolio;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.BankManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.InventoryManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.PPOFlipperStarConfig;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreClient;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tracks what's actually held and the cost basis built up from completed GE trades (persisted,
 * since the client doesn't track "what did I pay for this" anywhere itself).
 *
 * <p><b>Deliberate change from ge-star-v2's design: inventory + bank, not inventory-only.</b>
 * The sibling {@code ge-star-v2} plugin's {@code GeStarPortfolio} counts inventory only, because
 * {@code Rs2Bank}'s cache ({@code Rs2BankData}) is only populated reactively once the bank has
 * actually been opened this session - a bank read on a session where the bank was never opened
 * (or was opened long ago and has since changed) can be stale or empty, silently under/over-
 * reporting what's held. That plugin chose to stay simple and trustworthy over reaching further.
 *
 * <p>PPOFlipperStar makes the opposite call on purpose: an autonomous flipping policy (this
 * plugin's whole point, once later milestones wire it up) needs to reason about total exposure
 * across inventory AND bank to size new positions sensibly - treating banked stock as invisible
 * would make it buy more of something it already holds a pile of in the bank. To make that
 * trustworthy rather than repeating ge-star-v2's stale-cache risk, {@link BankManager} is used
 * to proactively open/refresh the bank on a slow, configurable interval (see
 * {@code PPOFlipperStarConfig#bankRefreshIntervalSeconds()}, off/0 by default so no unnecessary
 * bank trips happen unless the user opts in) specifically so this class's bank-side numbers are
 * kept fresh instead of read-and-hope. For a user who prefers the old, more conservative
 * behavior, {@code PPOFlipperStarConfig#inventoryOnlyMode()} makes this class fall back to
 * inventory-only holdings, matching ge-star-v2 exactly.
 *
 * <p><b>Persistence:</b> hand-rolled JSON via Gson through {@link ConfigManager}'s plain
 * {@code String} overloads, never its generic {@code setConfiguration(group, key, Object)}
 * overload - see {@link BuyLimitLedger}'s javadoc for why (that overload silently produces
 * invalid JSON for a plain {@code Map}).
 */
@Slf4j
@Singleton
public class PortfolioManager {

    private static final String CONFIG_GROUP = "ppoflipperstar";
    private static final String LEDGER_KEY = "portfolioCostBasisLedger";
    private static final Type LEDGER_TYPE = new TypeToken<Map<Integer, CostBasisEntry>>() {}.getType();

    private final ConfigManager configManager;
    private final InventoryManager inventoryManager;
    private final BankManager bankManager;
    private final PPOFlipperStarConfig config;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final Gson gson = new Gson();

    private final Map<Integer, CostBasisEntry> ledger;

    @Inject
    public PortfolioManager(ConfigManager configManager, InventoryManager inventoryManager,
                             BankManager bankManager, PPOFlipperStarConfig config,
                             PPOFlipperStarFirestoreSync firestoreSync) {
        this.configManager = configManager;
        this.inventoryManager = inventoryManager;
        this.bankManager = bankManager;
        this.config = config;
        this.firestoreSync = firestoreSync;
        this.ledger = loadLedger();
    }

    /**
     * Reconciles the local ledger against a Firestore pull, Firestore winning per this project's
     * "Firestore is the source of truth" decision - see {@link PPOFlipperStarFirestoreSync}'s
     * javadoc for when/how this is called (once at startup, best-effort, never blocking local
     * operation if the pull itself failed). A remote entry fully replaces the local entry for
     * that item id; local items with no remote counterpart are left untouched (Firestore not
     * having seen them yet, e.g. their very first push hasn't completed) rather than deleted.
     */
    public synchronized void reconcileFromFirestore(Map<Integer, PPOFlipperStarFirestoreClient.RemotePortfolioEntry> remoteEntries) {
        if (remoteEntries == null || remoteEntries.isEmpty()) return;
        for (PPOFlipperStarFirestoreClient.RemotePortfolioEntry remote : remoteEntries.values()) {
            CostBasisEntry entry = new CostBasisEntry(remote.itemId);
            if (remote.quantityHeld > 0) {
                entry.recordBuy(remote.quantityHeld, remote.totalCostBasis, remote.weightedAcquisitionTimestampMillis);
            }
            if (remote.realizedProfit != 0) {
                entry.addRealizedProfit(remote.realizedProfit);
            }
            ledger.put(remote.itemId, entry);
        }
        persistLedger();
        log.info("PPOFlipperStar: reconciled {} portfolio entr{} from Firestore.", remoteEntries.size(),
            remoteEntries.size() == 1 ? "y" : "ies");
    }

    private void pushToFirestore(int itemId) {
        if (!firestoreSync.isEnabled()) return;
        CostBasisEntry entry = ledger.get(itemId);
        if (entry == null) return;
        firestoreSync.pushPortfolioEntryAsync(itemId, entry.getQuantityHeld(), entry.getTotalCostBasis(),
            entry.getRealizedProfit(), entry.getWeightedAcquisitionTimestampMillis());
    }

    private Map<Integer, CostBasisEntry> loadLedger() {
        String json = configManager.getConfiguration(CONFIG_GROUP, LEDGER_KEY);
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<Integer, CostBasisEntry> loaded = gson.fromJson(json, LEDGER_TYPE);
            return loaded != null ? new HashMap<>(loaded) : new HashMap<>();
        } catch (JsonSyntaxException e) {
            log.warn("PPOFlipperStar: portfolio ledger config was not valid JSON, resetting it - {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void persistLedger() {
        configManager.setConfiguration(CONFIG_GROUP, LEDGER_KEY, gson.toJson(ledger, LEDGER_TYPE));
    }

    /**
     * Total quantity of an item held right now. Inventory + bank by default, or inventory-only
     * if {@code inventoryOnlyMode} is enabled - see this class's javadoc.
     */
    public int getHeldQuantity(int itemId) {
        int held = inventoryManager.getQuantity(itemId);
        if (!config.inventoryOnlyMode()) {
            held += bankManager.snapshotByItemId().getOrDefault(itemId, 0);
        }
        return held;
    }

    public int getHeldQuantity(String itemName) {
        int itemId = itemManager.getItemId(itemName);
        return itemId > 0 ? getHeldQuantity(itemId) : 0;
    }

    /** Snapshot of every item currently held (inventory, plus bank unless inventory-only mode is on), keyed by item id. */
    public Map<Integer, Integer> getAllHoldings() {
        Map<Integer, Integer> holdings = new HashMap<>(inventoryManager.snapshotByItemId());
        if (!config.inventoryOnlyMode()) {
            bankManager.snapshotByItemId().forEach((itemId, qty) -> holdings.merge(itemId, qty, Integer::sum));
        }
        return holdings;
    }

    /** Weighted-average cost per unit for an item, from the persisted ledger. 0 if never bought (or fully sold back out) through this ledger. */
    public int getAverageCost(int itemId) {
        CostBasisEntry entry = ledger.get(itemId);
        return entry != null ? entry.getAverageCost() : 0;
    }

    /** Records a completed buy fill against the cost-basis ledger and persists it. Call once per completed BUY offer, with the actual quantity/gp spent (not the requested order size - a partial fill only cost-bases what actually filled). */
    public synchronized void recordBuy(int itemId, int quantity, long totalSpent, long timestampMillis) {
        if (quantity <= 0) return;
        ledger.computeIfAbsent(itemId, CostBasisEntry::new).recordBuy(quantity, totalSpent, timestampMillis);
        persistLedger();
        pushToFirestore(itemId);
    }

    /** Records a completed sell fill against the cost-basis ledger and persists it, realizing profit/loss on the sold portion. */
    public synchronized void recordSell(int itemId, int quantity, long totalReceived) {
        if (quantity <= 0) return;
        ledger.computeIfAbsent(itemId, CostBasisEntry::new).recordSell(quantity, totalReceived);
        persistLedger();
        pushToFirestore(itemId);
    }

    public long getRealizedProfit(int itemId) {
        CostBasisEntry entry = ledger.get(itemId);
        return entry != null ? entry.getRealizedProfit() : 0;
    }

    /** Total realized profit/loss across every item ever traded through this ledger. */
    public long getTotalRealizedProfit() {
        return ledger.values().stream().mapToLong(CostBasisEntry::getRealizedProfit).sum();
    }

    /**
     * Total unrealized P&L across every open position, valuing each at its current live wiki
     * insta-sell price (what could actually be recovered by selling now) if provided, falling
     * back to average cost (0 unrealized) for any item the price lookup has no data for.
     */
    public long getTotalUnrealizedProfit(Map<Integer, Integer> currentPricesByItemId) {
        long total = 0;
        for (CostBasisEntry entry : ledger.values()) {
            if (entry.getQuantityHeld() <= 0) continue;
            Integer price = currentPricesByItemId.get(entry.getItemId());
            if (price != null) {
                total += entry.getUnrealizedProfit(price);
            }
        }
        return total;
    }

    /** Every item with a nonzero tracked position, for portfolio overviews. */
    public List<CostBasisEntry> getOpenPositions() {
        return ledger.values().stream()
            .filter(e -> e.getQuantityHeld() > 0)
            .collect(Collectors.toList());
    }

    public String getItemName(int itemId) {
        ItemComposition composition = itemManager.getItemComposition(itemId);
        return composition != null ? composition.getName() : ("item " + itemId);
    }
}
