package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.GeStarPortfolio;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

/**
 * Guardrail checks applied to a {@link GeStarOrder} before it's ever submitted to the GE.
 * Kept separate from the script's state machine so the rules are easy to read and to test
 * in isolation from the click/widget logic.
 */
@Slf4j
public class GeStarGuardrails {

    private final GeStarV2Config config;
    private final GeStarPortfolio portfolio;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final GeStarWikiPriceClient wikiPriceClient = new GeStarWikiPriceClient();

    @Getter
    private long gpSpentThisSession = 0;

    public GeStarGuardrails(GeStarV2Config config, GeStarPortfolio portfolio) {
        this.config = config;
        this.portfolio = portfolio;
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
        // Not a risk/safety tradeoff like the checks below - an order to sell more than is
        // held anywhere can never succeed, it would just sit forever failing the
        // PREPARING_FUNDS_OR_ITEMS bank-withdrawal step. Always rejected regardless of the
        // guardrails master switch.
        if (order.getAction() == GrandExchangeAction.SELL) {
            int held = portfolio.getHeldQuantity(order.getItemName());
            if (order.getQuantity() > held) {
                return String.format(
                    "sell quantity %d exceeds what's held (%d across bank + inventory)",
                    order.getQuantity(), held);
            }
        }

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

        if (order.getAction() == GrandExchangeAction.BUY) {
            int maxBaseValueDeviation = config.maxBaseValueDeviationPercent();
            if (maxBaseValueDeviation > 0) {
                String reason = checkBaseValueDeviation(order, maxBaseValueDeviation);
                if (reason != null) return reason;
            }
        }

        return null;
    }

    /**
     * Catches what {@link #checkPriceDeviation} structurally cannot: the live market price
     * itself having drifted far from what an item is normally worth, not just an order priced
     * worse than a (possibly also-distorted) live price. Compares against
     * {@link ItemComposition#getPrice()} - the client's own static item-definition price,
     * read locally with no network call, completely independent of live trading - so a thin,
     * cheap item whose live insta-buy has been pushed way up by one or two trades still gets
     * caught here even though the live-price guardrail would see the order as right in line
     * with (an already-bad) live price. Live-reported case this was added for: onion seed
     * (base value 3gp) bought at 10gp - a legitimate live insta-buy price at the time, but
     * over 3x the item's normal value.
     */
    private String checkBaseValueDeviation(GeStarOrder order, int maxDeviationPercent) {
        int itemId = itemManager.getItemId(order.getItemName());
        if (itemId <= 0) {
            return null;
        }

        ItemComposition composition = itemManager.getItemComposition(itemId);
        if (composition == null) {
            return null;
        }

        int baseValue = composition.getPrice();
        if (baseValue <= 0) {
            return null;
        }

        double deviation = (order.getPrice() - baseValue) / (double) baseValue * 100.0;
        if (deviation > maxDeviationPercent) {
            return String.format(
                "price %d gp is %.1f%% above base item value %d gp (max allowed: %d%%)",
                order.getPrice(), deviation, baseValue, maxDeviationPercent);
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

        // Uses GeStarWikiPriceClient (a direct call to the OSRS Wiki's real-time API), not
        // Rs2GrandExchange.getRealTimePrices - that Microbot Hub utility calls getPrice/
        // getSellPrice first, which hit ge-tracker.com's API (a third-party aggregator, not
        // the wiki) and only fall back to the wiki if that call fails outright (verified
        // against its bytecode - see GeStarWikiPriceClient's javadoc, added after this exact
        // mistake caused a live bad price clamp elsewhere in this plugin). ge-tracker's price
        // can drift badly from the real market on low-volume items.
        GeStarWikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null) {
            return null;
        }

        // Compare against the side of the book this order actually competes with - a buy
        // order competes with other buyers (recent insta-buy price), a sell order with other
        // sellers (recent insta-sell price). Comparing against the wrong side doubles the
        // apparent "deviation" for any item with a real buy/sell spread.
        int guidePrice = order.getAction() == GrandExchangeAction.BUY ? price.instaBuyPrice : price.instaSellPrice;
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
