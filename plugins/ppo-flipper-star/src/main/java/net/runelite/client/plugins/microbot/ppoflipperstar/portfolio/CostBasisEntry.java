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

    public CostBasisEntry(int itemId) {
        this.itemId = itemId;
    }

    public int getAverageCost() {
        if (quantityHeld <= 0) return 0;
        return (int) (totalCostBasis / quantityHeld);
    }

    /** How long the current position has been held, weighted-average across topped-up buys. 0 if nothing is currently held. */
    public long getHoldingDurationMillis(long nowMillis) {
        return quantityHeld > 0 ? Math.max(0, nowMillis - weightedAcquisitionTimestampMillis) : 0;
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
