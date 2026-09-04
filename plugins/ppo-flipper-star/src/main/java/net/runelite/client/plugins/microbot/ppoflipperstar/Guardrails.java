package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
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
    private final DecisionEngine decisionEngine;
    private final WikiPriceClient wikiPriceClient = new WikiPriceClient();

    @Getter
    private long gpSpentThisSession = 0;

    public Guardrails(PPOFlipperStarConfig config, PortfolioManager portfolio, BuyLimitLedger buyLimitLedger,
                       OrderQueue queue, DecisionEngine decisionEngine) {
        this.config = config;
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.queue = queue;
        this.decisionEngine = decisionEngine;
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
        // Sell-off mode (see its config description) is a deliberate SELL-path test - reject
        // every BUY outright while it's on, autonomous or manual, regardless of the guardrails
        // master switch. Same "always rejected" category as the checks below: this is a mode
        // invariant, not a tunable safety threshold.
        if (config.sellOffModeEnabled() && order.getAction() == GrandExchangeAction.BUY) {
            return "sell-off mode is on - BUY orders are rejected until it's turned off";
        }

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

        // Inventory-space check: a filled BUY always lands in inventory (GE collection, not the
        // bank), regardless of inventoryOnlyMode (that config only changes how holdings are
        // COUNTED for portfolio/sell purposes, not where a BUY physically lands) - so a BUY that
        // would need a new inventory slot can never actually complete once the inventory is full,
        // same "can never succeed" category as the checks above. Always rejected regardless of
        // the guardrails master switch.
        if (order.getAction() == GrandExchangeAction.BUY) {
            String reason = checkInventorySpace(order);
            if (reason != null) return reason;
        }

        if (!config.guardrailsEnabled()) {
            return null;
        }

        int maxQty = config.maxQuantityPerItem();
        if (maxQty > 0 && order.getQuantity() > maxQty) {
            return String.format("quantity %d exceeds max quantity per item (%d)", order.getQuantity(), maxQty);
        }

        // Real incident: the model submitted a real SELL for 18x water rune @ 6gp - a 108gp total
        // order. Not wrong on its own terms (the price/quantity were both individually sane), but
        // a GE slot, a fill wait, and the attention to notice/react to it are worth more than the
        // gp involved either way - not worth doing at all regardless of direction. Applies to
        // both BUY and SELL for the same reason (a trivially small BUY wastes a slot identically).
        long minOrderValue = config.minOrderValueGp();
        if (minOrderValue > 0 && order.totalValue() < minOrderValue) {
            return String.format(
                "total order value %d gp is below the minimum worth trading (%d gp)",
                order.totalValue(), minOrderValue);
        }

        if (order.getAction() == GrandExchangeAction.BUY) {
            // Checked before the session-total cap below, deliberately: a single order can never
            // consume more than this much regardless of how much session budget remains, whereas
            // the session cap alone would let one large order eat the entire remaining budget in
            // one shot (found live: a single BUY_SMALL tier order came out to 7.58M gp - "small"
            // in the model's own action-tier sense, not in real gp terms, since tier sizing scales
            // off an item's GE buy limit, not its price - see PPOFlipperStarConfig's
            // maxGpPerOrder description).
            long maxGpPerOrder = config.maxGpPerOrder();
            if (maxGpPerOrder > 0 && order.totalValue() > maxGpPerOrder) {
                return String.format(
                    "would spend %d gp in a single order, exceeding the per-order cap (%d gp)",
                    order.totalValue(), maxGpPerOrder);
            }

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
     *
     * <p><b>Deliberately reads {@code mapping.tradeLimitPer4Hours} directly, NOT
     * {@code mapping.hasTradeLimit()}/{@code getEffectiveTradeLimit()}</b> - found live, via
     * shadow-mode testing, that those two methods are not what their names suggest: verified
     * against the client jar's bytecode, {@code hasTradeLimit()} is
     * {@code tradeLimitPer4Hours > 0 && tradeLimitPer4Hours < 1000} (a bounded range check, not
     * a "do we have real limit data" check) and {@code getEffectiveTradeLimit()} clamps anything
     * {@code >= 1000} down to a flat 500. Confirmed against the OSRS Wiki's own public mapping
     * data that this guardrail was silently not enforcing for the overwhelming majority of real
     * tradeable items - Fishing bait (real limit 8000), Flax (13000), Emerald (13000), Adamant
     * dart (11000), Atlatl dart (11000) all failed {@code hasTradeLimit()} and fell through to
     * "not enforced" here despite having perfectly real limit data available. The raw field is a
     * plain {@code int} with {@code -1} as its "no data" sentinel (see
     * {@code Rs2GrandExchange.fetchItemMappingData}'s bytecode) - any other value, including one
     * {@code >= 1000}, is the item's genuine GE buy limit and must be enforced as such.
     *
     * <p><b>Reads {@link DecisionEngine#getBuyLimit}, NOT {@code Rs2GrandExchange.getItemMappingData}
     * directly</b> - a real incident (see {@code incident-notes/2026-09-03-decide-tick-bug-hunt.md}
     * item #13): the wiki has no per-item mapping endpoint, so {@code getItemMappingData(itemId)}
     * downloads the entire ~4,700-item, ~860KB mapping file on every single call regardless of
     * which item was asked for, and its own cache is keyed per-item so it never benefits from the
     * bulk download it just did. This guardrail runs on every BUY submission attempt (the main
     * tick thread, not a background one) - re-fetching that whole file per order is exactly the
     * same disease {@link DecisionEngine#refreshItemMappings} already fixed once for the DECIDE
     * loop's own per-item mapping lookups; routing through its shared, already-bulk-warmed cache
     * here closes the one remaining call site still bypassing that fix.
     */
    private String checkBuyLimit(PPOFlipperOrder order) {
        int itemId = order.getItemId() > 0 ? order.getItemId() : Rs2ItemManager.getItemIdByName(order.getItemName(), true);
        if (itemId <= 0) {
            return null;
        }

        int limit = decisionEngine.getBuyLimit(itemId);
        if (limit <= 0) {
            return null;
        }

        int alreadyBought = buyLimitLedger.quantityBoughtInWindow(itemId, System.currentTimeMillis());
        if (alreadyBought + order.getQuantity() > limit) {
            return String.format(
                "buy quantity %d would exceed the GE limit (%d already bought in the last 4h, limit %d)",
                order.getQuantity(), alreadyBought, limit);
        }

        return null;
    }

    /**
     * Rejects a BUY that would need a new inventory slot when there isn't one free. A stack of an
     * item already held in inventory doesn't need a new slot (OSRS stacks same-item quantities
     * into the one slot they already occupy, noted or not), so this only blocks when the item
     * isn't already present AND inventory is full - never blocks topping up an existing stack.
     *
     * <p>Deliberately unconditional on {@code inventoryOnlyMode} - that config only changes how
     * {@link PortfolioManager} COUNTS holdings (inventory-only vs. inventory+bank) for sell/
     * portfolio purposes, it has no bearing on where a BUY's GE collection physically lands. A
     * BUY always fills to inventory; this is a physical-space check, not a portfolio-accounting
     * one, so it applies the same regardless of that setting.
     */
    private String checkInventorySpace(PPOFlipperOrder order) {
        int itemId = order.getItemId() > 0 ? order.getItemId() : Rs2ItemManager.getItemIdByName(order.getItemName(), true);
        boolean alreadyStacking = itemId > 0 ? Rs2Inventory.hasItem(itemId) : Rs2Inventory.hasItem(order.getItemName());
        if (!alreadyStacking && Rs2Inventory.isFull()) {
            return String.format(
                "inventory is full - no free slot for a new BUY of %s (not already held)",
                order.getItemName());
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
        int itemId = order.getItemId() > 0 ? order.getItemId() : Rs2ItemManager.getItemIdByName(order.getItemName(), true);
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
