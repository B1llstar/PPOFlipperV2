package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

/**
 * Guardrail checks applied to a {@link PPOFlipperOrder} before it's ever submitted to the GE -
 * identical enforcement whether the order came from a human's right-click/panel action (the
 * only source that exists in this milestone) or, in a later milestone, the PPO policy. Kept
 * separate from the script's state machine so the rules are easy to read and test in isolation
 * from the click/widget logic.
 */
@Slf4j
public class Guardrails {

    private final PPOFlipperStarConfig config;
    private final PortfolioManager portfolio;
    private final BuyLimitLedger buyLimitLedger;
    private final OrderQueue queue;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final WikiPriceClient wikiPriceClient = new WikiPriceClient();

    @Getter
    private long gpSpentThisSession = 0;

    public Guardrails(PPOFlipperStarConfig config, PortfolioManager portfolio, BuyLimitLedger buyLimitLedger, OrderQueue queue) {
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
     * Returns null if the order clears every guardrail, otherwise a human-readable reason it
     * was rejected.
     */
    public String check(PPOFlipperOrder order) {
        // Not a risk/safety tradeoff like the checks below - an order to sell more than is held
        // anywhere can never succeed, it would just sit forever failing a bank-withdrawal step.
        // Always rejected regardless of the guardrails master switch.
        if (order.getAction() == GrandExchangeAction.SELL) {
            int held = portfolio.getHeldQuantity(order.getItemName());
            if (order.getQuantity() > held) {
                return String.format(
                    "sell quantity %d exceeds what's held (%d)",
                    order.getQuantity(), held);
            }
        }

        // Same reasoning: exceeding an item's GE buy limit can never succeed (the offer would
        // just sit un-fillable past the limit) - always rejected regardless of the guardrails
        // master switch. Checked against BuyLimitLedger's rolling 4h window (persisted across
        // sessions), not just this order in isolation.
        if (order.getAction() == GrandExchangeAction.BUY) {
            String reason = checkBuyLimit(order);
            if (reason != null) return reason;
        }

        // Duplicate-buy check: rejects a BUY for an item that already has an earlier
        // QUEUED/SUBMITTED BUY ahead of it in the queue, so the same item never gets bought
        // twice over before the first order has resolved. Always rejected regardless of the
        // guardrails master switch, same reasoning as the checks above.
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

        return null;
    }

    /**
     * Rejects a BUY that would push this session's (and prior sessions', via
     * {@link BuyLimitLedger}'s persisted rolling window) tracked purchases of this item past its
     * GE buy limit. The limit itself comes from {@link Rs2GrandExchange#getItemMappingData} - if
     * the item can't be resolved there, nothing is enforced (matches
     * {@link #checkPriceDeviation}'s same can't-resolve-so-don't-block stance).
     */
    private String checkBuyLimit(PPOFlipperOrder order) {
        int itemId = order.getItemId() > 0 ? order.getItemId() : itemManager.getItemId(order.getItemName());
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
    private String checkDuplicateBuy(PPOFlipperOrder order) {
        PPOFlipperOrder earliest = queue.getAll().stream()
            .filter(o -> o.getAction() == GrandExchangeAction.BUY)
            .filter(o -> o.getStatus() == PPOFlipperOrder.Status.QUEUED || o.getStatus() == PPOFlipperOrder.Status.SUBMITTED)
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

    private String checkPriceDeviation(PPOFlipperOrder order, int maxDeviationPercent) {
        int itemId = order.getItemId() > 0 ? order.getItemId() : itemManager.getItemId(order.getItemName());
        if (itemId <= 0) {
            // Can't resolve the item to check its guide price - let the GE search itself be the
            // source of truth for whether the name is valid, don't block on this.
            return null;
        }

        // Uses WikiPriceClient (a direct call to the OSRS Wiki's real-time API), never
        // Rs2GrandExchange.getRealTimePrices - see WikiPriceClient's javadoc.
        WikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null) {
            return null;
        }

        // Compare against the side of the book this order actually competes with - a buy order
        // competes with other buyers (recent insta-buy price), a sell order with other sellers
        // (recent insta-sell price).
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
