package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.WikiPrice;
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
        if (!config.guardrailsEnabled()) {
            return null;
        }

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

        // Rs2GrandExchange.getPrice(int) hits ge-tracker.com's derived "overall" price, which
        // can drift badly from the real market on low-volume items (seen: 103gp reported for
        // an item that actually buys/sells around 27gp). getRealTimePrices backs onto the
        // OSRS Wiki's real-time price API (the same data source the wiki itself and most
        // price checkers use) and only falls back to ge-tracker if the wiki has no data for
        // this item, so it's the more trustworthy number to guardrail against.
        WikiPrice wikiPrice = Rs2GrandExchange.getRealTimePrices(itemId);
        if (wikiPrice == null) {
            return null;
        }

        // Compare against the side of the book this order actually competes with - a buy
        // order competes with other buyers (recent buyPrice), a sell order with other
        // sellers (recent sellPrice). Comparing against the wrong side doubles the apparent
        // "deviation" for any item with a real buy/sell spread.
        int guidePrice = order.getAction() == GrandExchangeAction.BUY ? wikiPrice.buyPrice : wikiPrice.sellPrice;
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
