package net.runelite.client.plugins.microbot.ppoflipperstar.portfolio;

import lombok.Getter;

/**
 * Running average-cost position in one item, built up from completed GE buys and drawn down
 * by completed sells. Standard weighted-average-cost accounting (not FIFO/LIFO lot tracking) -
 * simpler to maintain and the right level of precision for a flip-margin estimate.
 */
@Getter
public class CostBasisEntry {

    private final int itemId;
    private int quantityHeld;
    private long totalCostBasis;
    private long realizedProfit;
    private long weightedAcquisitionTimestampMillis;

    // Any acquisition timestamp before this (2024-01-01T00:00:00Z) is treated as invalid data,
    // not a real position age - see getHoldingDurationMillis's javadoc for the real incident.
    // This plugin (and PPOFlipperStar as a concept) didn't exist before this date, so a genuine
    // acquisition can never legitimately predate it; anything that does is corrupted data (an
    // epoch-zero/near-zero timestamp from before PortfolioManager.reconcileFromFirestore's own
    // db06d77 fix existed, or some other path that never sanity-checked what it stored).
    private static final long EARLIEST_PLAUSIBLE_ACQUISITION_MILLIS = 1_704_067_200_000L;

    public CostBasisEntry(int itemId) {
        this.itemId = itemId;
    }

    public int getAverageCost() {
        if (quantityHeld <= 0) return 0;
        return (int) (totalCostBasis / quantityHeld);
    }

    /**
     * How long the current position has been held, weighted-average across topped-up buys. 0 if
     * nothing is currently held.
     *
     * <p><b>Also 0 if {@link #weightedAcquisitionTimestampMillis} is itself invalid</b> (see
     * {@link #EARLIEST_PLAUSIBLE_ACQUISITION_MILLIS}) - a real, recurring incident: db06d77 fixed
     * {@code PortfolioManager.reconcileFromFirestore} trusting an epoch-zero remote timestamp
     * verbatim, but that fix only guards ONE write path. An entry already corrupted before that
     * fix existed (or corrupted by some other path that never validated what it stored) persists
     * its bad timestamp indefinitely - {@link #recordBuy}'s weighted-average blend only DILUTES a
     * bad value proportionally to new purchase volume, it never resets it outright, so a stale
     * position with no further real buys keeps reporting a reported age of tens of millions of
     * minutes (confirmed live: "Ham robe ... held 14911037 min", ~28 years) forever. Checking here,
     * at the point of use, fixes this and any other already-corrupted entry immediately with no
     * data migration needed, and can't be reintroduced by some future write path that forgets to
     * validate its own input the way the reconcile fix required remembering to.
     */
    public long getHoldingDurationMillis(long nowMillis) {
        if (quantityHeld <= 0) return 0;
        if (weightedAcquisitionTimestampMillis < EARLIEST_PLAUSIBLE_ACQUISITION_MILLIS) return 0;
        return Math.max(0, nowMillis - weightedAcquisitionTimestampMillis);
    }

    /**
     * Unrealized profit/loss if the current position were liquidated at {@code currentPrice}
     * per unit right now. 0 if nothing is currently held.
     */
    public long getUnrealizedProfit(int currentPrice) {
        if (quantityHeld <= 0) return 0;
        return (long) quantityHeld * currentPrice - totalCostBasis;
    }

    /**
     * Records a completed buy: adds to the position and its cost basis, and blends the
     * position's acquisition timestamp the same quantity-weighted way totalCostBasis blends -
     * a fresh position (nothing currently held) sets the timestamp directly; topping up an
     * existing position shifts the effective acquisition time toward now, weighted by how much
     * of the resulting position the new buy represents.
     */
    public void recordBuy(int quantity, long totalSpent, long timestampMillis) {
        int previousQuantity = quantityHeld;
        quantityHeld += quantity;
        totalCostBasis += totalSpent;

        if (previousQuantity <= 0) {
            weightedAcquisitionTimestampMillis = timestampMillis;
        } else {
            weightedAcquisitionTimestampMillis =
                (weightedAcquisitionTimestampMillis * previousQuantity + timestampMillis * (long) quantity)
                    / quantityHeld;
        }
    }

    /**
     * Records a completed sell: draws down the position at the current average cost and
     * realizes profit/loss on the portion sold. Selling more than is tracked as held (e.g. an
     * item acquired before this ledger existed) is not an error - it just can't compute
     * realized profit for the untracked portion, so that excess sells at zero cost basis.
     * Deliberately does not touch weightedAcquisitionTimestampMillis - selling down a position
     * doesn't change when the remaining shares were (on average) acquired.
     */
    public void recordSell(int quantity, long totalReceived) {
        int soldFromTrackedPosition = Math.min(quantity, quantityHeld);
        long costOfSoldPortion = quantityHeld > 0
            ? (long) soldFromTrackedPosition * totalCostBasis / quantityHeld
            : 0;

        realizedProfit += totalReceived - costOfSoldPortion;
        quantityHeld -= soldFromTrackedPosition;
        totalCostBasis -= costOfSoldPortion;
    }

    /**
     * Adds a realized-profit delta directly, with no quantity/cost-basis change - used only when
     * reconciling from a Firestore pull (see {@code PortfolioManager#reconcileFromFirestore}),
     * where the remote document's realizedProfit is the authoritative total and this entry was
     * just freshly constructed from the remote quantityHeld/totalCostBasis via {@link #recordBuy}
     * (which does not touch realizedProfit itself, so there's no double-counting to worry about).
     */
    public void addRealizedProfit(long delta) {
        realizedProfit += delta;
    }
}
