package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coins in inventory + bank, and a session net-worth delta (a start-of-session snapshot,
 * captured once, vs. the live number now). Bank-side coins are subject to the same staleness
 * caveat as any other {@link BankManager} read (see that class's javadoc) - not a concern for
 * inventory coins, which are always live.
 */
@Singleton
public class GoldManager {

    private final InventoryManager inventoryManager;
    private final BankManager bankManager;
    private final PortfolioManager portfolioManager;
    private final PPOFlipperStarConfig config;

    private long sessionStartNetWorth = -1;

    // Real incident: PPOFlipperStarOverlay.render() (called on RuneLite's own client/render
    // thread, every single frame) called getTotalGold() directly, the same
    // getInventoryGold()/getBankGold() chain that blocks on a live bank scan - confirmed live via
    // jstack to freeze the client's UI for 155+ seconds when this same call was made from the
    // Swing EDT (see PPOFlipperStarPanel.refreshFromScriptState's own javadoc for that first
    // incident, and its fix - a background poll thread the panel reads a cached value from
    // instead). The panel's own fix is private to that class; this is the same fix moved into
    // GoldManager itself so every render-thread-sensitive caller (currently just the overlay, but
    // any future one too) can share one background poller instead of each needing its own.
    private final ScheduledExecutorService goldPollExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PPOFlipperStar-GoldManagerPoll");
            t.setDaemon(true);
            return t;
        });
    private volatile long cachedTotalGold = 0;
    private volatile boolean pollStarted = false;

    @Inject
    public GoldManager(InventoryManager inventoryManager, BankManager bankManager,
                        PortfolioManager portfolioManager, PPOFlipperStarConfig config) {
        this.inventoryManager = inventoryManager;
        this.bankManager = bankManager;
        this.portfolioManager = portfolioManager;
        this.config = config;
    }

    /**
     * The same value {@link #getTotalGold()} would compute, but read from a background poll
     * (refreshed every second) instead of scanning live - see this class's poller field javadoc
     * for the real incident this exists to prevent. Starts the poller lazily on first call rather
     * than in the constructor, so a caller that never needs the cached value (e.g. DecisionEngine,
     * which calls {@link #getTotalGold()} directly and deliberately wants a fresh read once per
     * DECIDE tick) never pays for a background thread it doesn't use.
     */
    public long getCachedTotalGold() {
        if (!pollStarted) {
            synchronized (this) {
                if (!pollStarted) {
                    cachedTotalGold = getTotalGold();
                    goldPollExecutor.scheduleWithFixedDelay(() -> {
                        try {
                            cachedTotalGold = getTotalGold();
                        } catch (Exception e) {
                            // Best-effort - a transient failure just means this keeps returning
                            // the last known value until the next poll succeeds.
                        }
                    }, 1, 1, TimeUnit.SECONDS);
                    pollStarted = true;
                }
            }
        }
        return cachedTotalGold;
    }

    public int getInventoryGold() {
        return inventoryManager.getQuantity(ItemID.COINS);
    }

    public int getBankGold() {
        if (config.inventoryOnlyMode()) return 0;
        return bankManager.snapshotByItemId().getOrDefault(ItemID.COINS, 0);
    }

    public long getTotalGold() {
        return (long) getInventoryGold() + getBankGold();
    }

    /**
     * Net worth right now: gold on hand plus every open position valued at its own cost basis
     * (not a live market price - this is a conservative "what have I actually put in" figure,
     * distinct from {@link PortfolioManager#getTotalUnrealizedProfit}'s live-marked P&L).
     */
    public long getNetWorthAtCostBasis() {
        long positionsValue = portfolioManager.getOpenPositions().stream()
            .mapToLong(e -> (long) e.getQuantityHeld() * e.getAverageCost())
            .sum();
        return getTotalGold() + positionsValue;
    }

    /** Captures the current net worth as this session's starting point. Call once, at script start. */
    public void snapshotSessionStart() {
        sessionStartNetWorth = getNetWorthAtCostBasis();
    }

    /** Live net worth minus the session-start snapshot, or 0 if no snapshot has been taken yet. */
    public long getSessionNetWorthDelta() {
        if (sessionStartNetWorth < 0) return 0;
        return getNetWorthAtCostBasis() - sessionStartNetWorth;
    }

    public boolean hasSessionSnapshot() {
        return sessionStartNetWorth >= 0;
    }
}
