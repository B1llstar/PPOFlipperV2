package net.runelite.client.plugins.microbot.ppoflipperstar;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;

import javax.inject.Inject;
import javax.inject.Singleton;

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

    @Inject
    public GoldManager(InventoryManager inventoryManager, BankManager bankManager,
                        PortfolioManager portfolioManager, PPOFlipperStarConfig config) {
        this.inventoryManager = inventoryManager;
        this.bankManager = bankManager;
        this.portfolioManager = portfolioManager;
        this.config = config;
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
