package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Set;

/**
 * The standalone "Rapid non-PPO" flipping sub-mode (see {@link PPOFlipperStarConfig}'s "Rapid
 * flipping" section) - entirely independent of the PPO model/{@link DecisionEngine}. On its own
 * periodic cadence ({@code rapidScanIntervalSeconds}), evaluates every item in scope
 * ({@code rapidScanAllItems} on/off - see {@link #itemsInScope}) via {@link RapidFlipEngine} and
 * queues a BUY for anything currently clearing the configured margin bar, sized by
 * {@code rapidFlipBudgetGp} and capped at the item's own GE 4-hour buy limit.
 *
 * <p>Produces ordinary {@link PPOFlipperOrder}s pushed through the exact same
 * {@link OrderQueue#add}/{@link Guardrails#check}/submission pipeline as every other order in this
 * plugin - buy limits, cost-basis tracking, and the GE active-offer slot cap are all respected
 * automatically, nothing bypassed for speed (per this feature's own design decision - see the
 * config section's description). The SELL half of a completed rapid flip is queued separately,
 * once the BUY is observed to have filled - see {@code PPOFlipperStarScript#checkForFinishedOffers}
 * and {@link PPOFlipperOrder#isRapidFlipBuy()}'s own javadoc for that half of the flow.
 *
 * <p>Called from {@code PPOFlipperStarScript#tick()} on its own timer, independent of
 * {@code maybeRunDecideTick} - deliberately not folded into the DECIDE phase, since rapid flipping
 * is specifically about reacting to a spread opening up between ticks, not waiting on the PPO
 * model's own (typically much slower) decision cadence.
 */
@Slf4j
@Singleton
public class RapidFlipScanner {

    private final PPOFlipperStarConfig config;
    private final RapidFlipEngine rapidFlipEngine;
    private final WatchlistManager watchlistManager;
    private final DecisionEngine decisionEngine;
    private final OrderQueue queue;
    private final WikiPriceClient wikiPriceClient;

    @Inject
    public RapidFlipScanner(PPOFlipperStarConfig config, RapidFlipEngine rapidFlipEngine,
                             WatchlistManager watchlistManager, DecisionEngine decisionEngine, OrderQueue queue,
                             WikiPriceClient wikiPriceClient) {
        this.config = config;
        this.rapidFlipEngine = rapidFlipEngine;
        this.watchlistManager = watchlistManager;
        this.decisionEngine = decisionEngine;
        this.queue = queue;
        this.wikiPriceClient = wikiPriceClient;
    }

    /** Every item id this scan considers - the watchlist, or every item the wiki's bulk mapping/price data knows about, per {@code rapidScanAllItems}. */
    private Set<Integer> itemsInScope() {
        if (config.rapidScanAllItems()) {
            return decisionEngine.getAllKnownItemIds();
        }
        return watchlistManager.getAll();
    }

    /**
     * Runs one scan pass: evaluates every item in scope and queues a BUY for anything that
     * qualifies and doesn't already have a pending rapid BUY/SELL - never blocks (reads only
     * already-cached live prices via {@link RapidFlipEngine}, same non-blocking contract as
     * {@link WikiPriceClient#getLatestPrice}), safe to call every tick regardless of
     * {@code rapidScanIntervalSeconds} (the caller is responsible for that cadence, same pattern
     * as {@code PPOFlipperStarScript#maybeRunDecideTick}).
     */
    public void scan() {
        if (!config.rapidNonPpoEnabled()) return;

        // Proactively warms the shared bulk price cache rather than assuming a DECIDE tick has
        // already done it - rapid non-PPO is designed to work with the PPO/DECIDE side entirely
        // disabled, so it cannot depend on that loop having run even once. A cheap no-op once
        // warm, same TTL-backed shape as DecisionEngine's own equivalent call.
        wikiPriceClient.refreshAllPrices();

        for (int itemId : itemsInScope()) {
            RapidFlipEngine.Evaluation evaluation = rapidFlipEngine.evaluate(itemId);
            if (evaluation == null || !evaluation.qualifies) continue;

            if (alreadyPending(itemId)) continue;

            String itemName = decisionEngine.getItemName(itemId);
            if (itemName == null) continue;

            int buyLimit = decisionEngine.getBuyLimit(itemId);
            int quantityByBudget = evaluation.instaBuyPrice > 0
                ? config.rapidFlipBudgetGp() / evaluation.instaBuyPrice
                : 0;
            int quantity = buyLimit > 0 ? Math.min(quantityByBudget, buyLimit) : quantityByBudget;
            if (quantity <= 0) continue;

            PPOFlipperOrder buyOrder = new PPOFlipperOrder(GrandExchangeAction.BUY, itemId, itemName,
                quantity, evaluation.instaBuyPrice);
            buyOrder.setRapidFlipBuy(true);
            queue.add(buyOrder);
            log.info("PPOFlipperStar: rapid flip - queued BUY {}x {} @ {} gp (net margin/unit {} gp, insta-sell {} gp)",
                quantity, itemName, evaluation.instaBuyPrice, evaluation.netMarginPerUnit, evaluation.instaSellPrice);
        }
    }

    /** True if a rapid BUY or its eventual SELL is already queued/submitted for this item - avoids piling up duplicate flips on the same item every scan while one is already in flight. */
    private boolean alreadyPending(int itemId) {
        return queue.getAll().stream()
            .anyMatch(o -> o.getItemId() == itemId
                && (o.getStatus() == PPOFlipperOrder.Status.QUEUED || o.getStatus() == PPOFlipperOrder.Status.SUBMITTED));
    }
}
