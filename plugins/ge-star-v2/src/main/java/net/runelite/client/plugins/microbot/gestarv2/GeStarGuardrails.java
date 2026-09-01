package net.runelite.client.plugins.microbot.gestarv2;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.GeStarPortfolio;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
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
    private final BuyLimitLedger buyLimitLedger;
    private final GeStarOrderQueue queue;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final GeStarWikiPriceClient wikiPriceClient = new GeStarWikiPriceClient();

    @Getter
    private long gpSpentThisSession = 0;

    public GeStarGuardrails(GeStarV2Config config, GeStarPortfolio portfolio, BuyLimitLedger buyLimitLedger, GeStarOrderQueue queue) {
        this.config = config;
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.queue = queue;
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
                    "sell quantity %d exceeds what's held (%d in inventory)",
                    order.getQuantity(), held);
            }
        }

        // Same reasoning as the SELL-vs-held check above: exceeding an item's GE buy limit can
        // never succeed (the offer would just sit un-fillable past the limit), it's not a risk/
        // safety tradeoff - always rejected regardless of the guardrails master switch. Checked
        // against BuyLimitLedger's rolling 4h window (persisted across sessions), not just this
        // order in isolation - a single order under the limit can still be rejected here if
        // enough was already bought recently.
        if (order.getAction() == GrandExchangeAction.BUY) {
            String reason = checkBuyLimit(order);
            if (reason != null) return reason;
        }

        // Second line of defense against duplicate same-item BUYs stacking up (the first is
        // FlipperStarEngine's own pendingBuyOrderIdsByItemId check, which only covers orders it
        // originates) - catches a duplicate from any other source (manual panel, web UI, a
        // future caller) that FlipperStar's own check can't see. Deliberately keyed on whether
        // this exact order is the FIRST QUEUED/SUBMITTED order for the item in the queue, not
        // "is there any other order for this item" - the earliest one must still be allowed
        // through, only a genuine duplicate behind it gets rejected. Always rejected regardless
        // of the guardrails master switch, same reasoning as the checks above: a duplicate can
        // never usefully coexist with the order ahead of it.
        if (order.getAction() == GrandExchangeAction.BUY) {
            String reason = checkDuplicateBuy(order);
            if (reason != null) return reason;
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
     * Rejects a BUY that would push this session's (and prior sessions', via
     * {@link BuyLimitLedger}'s persisted rolling window) tracked purchases of this item past its
     * GE buy limit. The limit itself comes from {@link Rs2GrandExchange#getItemMappingData},
     * the OSRS Wiki's item-mapping data (cached after first lookup, see that method's own
     * behavior) - if the item can't be resolved there, nothing is enforced (matches
     * {@link #checkPriceDeviation}'s same can't-resolve-so-don't-block stance).
     */
    private String checkBuyLimit(GeStarOrder order) {
        int itemId = itemManager.getItemId(order.getItemName());
        if (itemId <= 0) {
            return null;
        }

        ItemMappingData mapping = Rs2GrandExchange.getItemMappingData(itemId);
        if (mapping == null || !mapping.hasTradeLimit()) {
            return null;
        }

        int limit = mapping.getEffectiveTradeLimit();
        int alreadyBought = buyLimitLedger.quantityBoughtInWindow(itemId, System.currentTimeMillis());
        if (alreadyBought + order.getQuantity() > limit) {
            return String.format(
                "buy quantity %d would exceed the GE limit (%d already bought in the last 4h, limit %d)",
                order.getQuantity(), alreadyBought, limit);
        }

        return null;
    }

    /**
     * Rejects {@code order} if it's not the earliest still-active (QUEUED or SUBMITTED) BUY for
     * this item name already in the queue - i.e. something ahead of it for the same item hasn't
     * resolved yet, so this one is a duplicate riding along behind it. Order identity (not just
     * item name) is compared so this never rejects an order against itself.
     */
    private String checkDuplicateBuy(GeStarOrder order) {
        GeStarOrder earliest = queue.getAll().stream()
            .filter(o -> o.getAction() == GrandExchangeAction.BUY)
            .filter(o -> o.getStatus() == GeStarOrder.Status.QUEUED || o.getStatus() == GeStarOrder.Status.SUBMITTED)
            .filter(o -> o.getItemName().equalsIgnoreCase(order.getItemName()))
            .findFirst()
            .orElse(null);

        if (earliest != null && earliest.getId() != order.getId()) {
            return String.format(
                "duplicate BUY for %s - order [%d] for this item is already queued/submitted ahead of it",
                order.getItemName(), earliest.getId());
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
