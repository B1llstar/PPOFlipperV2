package net.runelite.client.plugins.microbot.gestarv2.portfolio;

import lombok.Getter;

/**
 * Running average-cost position in one item, built up from completed GE buys and drawn down
 * by completed sells. Standard weighted-average-cost accounting (not FIFO/LIFO lot tracking)
 * - simpler to maintain and the right level of precision for a flip-margin estimate.
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
     * Records a completed buy: adds to the position and its cost basis, and blends the
     * position's acquisition timestamp the same quantity-weighted way totalCostBasis blends -
     * a fresh position (nothing currently held) sets the timestamp directly; topping up an
     * existing position shifts the effective acquisition time toward now, weighted by how much
     * of the resulting position the new buy represents. This is a deliberate simplification vs.
     * FIFO lot tracking (matching the weighted-average-cost approach this class already uses for
     * gp): a recently topped-up position reports a more recent acquisition time than its oldest
     * lot, which is the right bias for an exit model - a recently-added chunk really does make
     * the aggregate position "fresher" for hold/sell purposes.
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
     * doesn't change when the remaining shares were (on average) acquired. If this sell empties
     * the position, the next recordBuy's previousQuantity <= 0 branch resets the timestamp
     * cleanly rather than blending against a now-stale value.
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
}
