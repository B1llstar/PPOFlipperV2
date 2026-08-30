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

    public CostBasisEntry(int itemId) {
        this.itemId = itemId;
    }

    public int getAverageCost() {
        if (quantityHeld <= 0) return 0;
        return (int) (totalCostBasis / quantityHeld);
    }

    /** Records a completed buy: adds to the position and its cost basis. */
    public void recordBuy(int quantity, long totalSpent) {
        quantityHeld += quantity;
        totalCostBasis += totalSpent;
    }

    /**
     * Records a completed sell: draws down the position at the current average cost and
     * realizes profit/loss on the portion sold. Selling more than is tracked as held (e.g. an
     * item acquired before this ledger existed) is not an error - it just can't compute
     * realized profit for the untracked portion, so that excess sells at zero cost basis.
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
