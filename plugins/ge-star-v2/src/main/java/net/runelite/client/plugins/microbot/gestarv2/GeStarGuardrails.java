package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

/**
 * Guardrail checks applied to a {@link GeStarOrder} before it's ever submitted to the GE.
 * Kept separate from the script's state machine so the rules are easy to read and to test
 * in isolation from the click/widget logic.
 */
@Slf4j
public class GeStarGuardrails {

    private final GeStarV2Config config;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();

    @Getter
    private long gpSpentThisSession = 0;

    public GeStarGuardrails(GeStarV2Config config) {
        this.config = config;
    }

    public void reset() {
        gpSpentThisSession = 0;
    }

    public void recordSpend(long gp) {
        gpSpentThisSession += gp;
    }

    /**
     * Returns null if the order clears every guardrail, otherwise a human-readable reason
     * it was rejected.
     */
    public String check(GeStarOrder order) {
        int maxQty = config.maxQuantityPerItem();
        if (maxQty > 0 && order.getQuantity() > maxQty) {
            return String.format("quantity %d exceeds max quantity per item (%d)", order.getQuantity(), maxQty);
        }

        if (order.getAction() == GrandExchangeAction.BUY) {
            int maxGp = config.maxGpToSpend();
            if (maxGp > 0 && gpSpentThisSession + order.totalValue() > maxGp) {
                return String.format(
                    "would spend %d gp, exceeding session cap (%d gp spent so far, %d gp cap)",
                    order.totalValue(), gpSpentThisSession, maxGp);
            }
        }

        int maxDeviation = config.maxPriceDeviationPercent();
        if (maxDeviation > 0) {
            String reason = checkPriceDeviation(order, maxDeviation);
            if (reason != null) return reason;
        }

        return null;
    }

    private String checkPriceDeviation(GeStarOrder order, int maxDeviationPercent) {
        int itemId = itemManager.getItemId(order.getItemName());
        if (itemId <= 0) {
            // Can't resolve the item to check its guide price - let the GE search itself
            // be the source of truth for whether the name is valid, don't block on this.
            return null;
        }

        int guidePrice = Rs2GrandExchange.getPrice(itemId);
        if (guidePrice <= 0) {
            return null;
        }

        double deviation = Math.abs(order.getPrice() - guidePrice) / (double) guidePrice * 100.0;
        if (deviation > maxDeviationPercent) {
            return String.format(
                "price %d gp is %.1f%% away from guide price %d gp (max allowed: %d%%)",
                order.getPrice(), deviation, guidePrice, maxDeviationPercent);
        }

        return null;
    }
}
