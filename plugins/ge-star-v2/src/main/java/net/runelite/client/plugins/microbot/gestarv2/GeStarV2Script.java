package net.runelite.client.plugins.microbot.gestarv2;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.GrandExchangeOfferDetails;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;
import net.runelite.client.plugins.microbot.gestarv2.portfolio.GeStarPortfolio;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * State machine that pulls queued orders from the shared {@link GeStarOrderQueue} (the same
 * queue the sidebar panel reads and lets the user add/remove from), submits offers through
 * {@link Rs2GrandExchange}, and updates each order's live status/fill as it progresses.
 * Guardrails from {@link GeStarGuardrails} are checked immediately before every submission.
 */
@Slf4j
public class GeStarV2Script extends Script {

    private static final int SCHEDULE_INTERVAL_MS = 600;

    enum State {
        IDLE,
        GOING_TO_GE,
        PREPARING_FUNDS_OR_ITEMS,
        SUBMITTING_ORDERS,
        MONITORING_OFFERS,
        DONE
    }

    private final GeStarOrderQueue queue;
    private final GeStarPortfolio portfolio;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final GeStarWikiPriceClient wikiPriceClient = new GeStarWikiPriceClient();

    private GeStarV2Config config;
    private GeStarGuardrails guardrails;

    private State state = State.IDLE;
    private final Map<GrandExchangeSlots, GeStarOrder> activeOrders = new LinkedHashMap<>();
    private GeStarOrder orderAwaitingFunds;
    private GeStarOrder lastFundsShortfallOrder;

    // True right after Execute, until the first reconcile pass has run once the GE is open.
    // Needed because Stop never cancels real in-game offers (it only stops this script's own
    // loop) - a SUBMITTED order may still have a genuinely live GE offer behind it, so on
    // resume every SUBMITTED order must be checked against what's actually on the GE before
    // deciding whether to treat it as still-active or truly orphaned. Blindly resetting every
    // SUBMITTED order back to QUEUED on every Execute (the previous behavior) resubmitted
    // orders that were already live, buying/selling the same thing twice.
    private boolean needsReconcile = false;

    @Inject
    public GeStarV2Script(GeStarOrderQueue queue, GeStarPortfolio portfolio) {
        this.queue = queue;
        this.portfolio = portfolio;
    }

    public boolean run(GeStarV2Config config) {
        this.config = config;
        this.guardrails = new GeStarGuardrails(config, portfolio);
        this.guardrails.reset();
        this.state = State.GOING_TO_GE;
        this.activeOrders.clear();
        this.orderAwaitingFunds = null;
        this.lastFundsShortfallOrder = null;
        this.needsReconcile = true;

        Rs2AntibanSettings.naturalMouse = true;
        Rs2Antiban.setActivityIntensity(ActivityIntensity.LOW);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                tick();
            } catch (Exception ex) {
                log.error("Error in GeStarV2Script: {} - ", ex.getMessage(), ex);
            }
        }, 0, SCHEDULE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        activeOrders.clear();
        orderAwaitingFunds = null;
        lastFundsShortfallOrder = null;
        state = State.IDLE;
        super.shutdown();
    }

    /**
     * Runs once, right after Execute, the first time the GE interface is confirmed open.
     * Stopping the script never cancels real in-game offers - only the previous run's
     * in-memory {@code activeOrders} map is lost - so every order still marked SUBMITTED from
     * before needs to be checked against what's actually live on the GE right now, rather than
     * assumed orphaned. A SUBMITTED order that matches a live offer gets its slot restored
     * into activeOrders (monitoring picks it back up exactly where it left off); only orders
     * with no matching live offer (e.g. a genuinely lost/collected-outside-the-script offer)
     * get reset back to QUEUED for resubmission.
     *
     * <p>Also adopts any live GE slot that isn't accounted for by the queue at all - an offer
     * placed manually, by another tool, or left over from before this plugin was ever run. Left
     * unadopted, these were invisible to maxActiveOffers (letting the script try to use a slot
     * that's actually occupied) and would never get collected/cost-based by this script. Adopted
     * as a synthetic GeStarOrder built from the live offer's own details, so it flows through
     * the exact same monitor/collect/recordCostBasis path as any order this script submitted
     * itself.
     */
    private void reconcileSubmittedOrders() {
        List<GeStarOrder> submittedOrders = new ArrayList<>(queue.getByStatus(GeStarOrder.Status.SUBMITTED));

        for (GrandExchangeSlots slot : Rs2GrandExchange.getActiveOfferSlots()) {
            GrandExchangeOfferDetails details = Rs2GrandExchange.getOfferDetails(slot);
            if (details == null) continue;

            GrandExchangeAction liveAction = details.isSelling() ? GrandExchangeAction.SELL : GrandExchangeAction.BUY;

            GeStarOrder match = submittedOrders.stream()
                .filter(o -> !activeOrders.containsValue(o))
                .filter(o -> o.getAction() == liveAction)
                .filter(o -> o.getItemName().equalsIgnoreCase(details.getItemName()))
                .filter(o -> o.getQuantity() == details.getTotalQuantity())
                .filter(o -> o.getPrice() == details.getPrice())
                .findFirst()
                .orElse(null);

            if (match != null) {
                match.setSlot(slot);
                match.setQuantityFilled(liveAction == GrandExchangeAction.BUY
                    ? Rs2GrandExchange.getItemsBoughtFromOffer(slot)
                    : Rs2GrandExchange.getItemsSoldFromOffer(slot));
                activeOrders.put(slot, match);
                log.info("GE Star V2: reconciled SUBMITTED order {} to live slot {}", match, slot);
            } else {
                GeStarOrder adopted = new GeStarOrder(liveAction, details.getItemName(), details.getTotalQuantity(), details.getPrice());
                adopted.setSlot(slot);
                adopted.setStatus(GeStarOrder.Status.SUBMITTED);
                adopted.setQuantityFilled(liveAction == GrandExchangeAction.BUY
                    ? Rs2GrandExchange.getItemsBoughtFromOffer(slot)
                    : Rs2GrandExchange.getItemsSoldFromOffer(slot));
                queue.add(adopted);
                activeOrders.put(slot, adopted);
                log.info("GE Star V2: adopted untracked live offer in slot {} - {}", slot, adopted);
            }
        }

        int orphaned = 0;
        for (GeStarOrder order : submittedOrders) {
            if (!activeOrders.containsValue(order)) {
                order.setStatus(GeStarOrder.Status.QUEUED);
                orphaned++;
            }
        }

        if (orphaned > 0) {
            log.warn("GE Star V2: {} SUBMITTED order(s) had no matching live GE offer, re-queued for resubmission", orphaned);
        }
        queue.notifyChanged();
    }

    private void tick() {
        switch (state) {
            case IDLE:
                break;

            case GOING_TO_GE:
                if (Rs2GrandExchange.isOpen()) {
                    if (needsReconcile) {
                        reconcileSubmittedOrders();
                        needsReconcile = false;
                    }
                    state = State.SUBMITTING_ORDERS;
                    return;
                }
                Rs2GrandExchange.walkToGrandExchange();
                if (Rs2GrandExchange.openExchange()) {
                    if (needsReconcile) {
                        reconcileSubmittedOrders();
                        needsReconcile = false;
                    }
                    state = State.SUBMITTING_ORDERS;
                }
                break;

            case PREPARING_FUNDS_OR_ITEMS:
                prepareFundsOrItems();
                break;

            case SUBMITTING_ORDERS:
                submitNextOrder();
                break;

            case MONITORING_OFFERS:
                monitorOffers();
                break;

            case DONE:
                // Only actually stop if configured to - otherwise stay in DONE, idling on this
                // same fixed-delay tick loop, and re-check the queue every tick so an order
                // added later (e.g. by FlipperStar's autonomous scan, long after this script
                // reached DONE) gets picked up without needing Execute clicked again. Without
                // this re-check, DONE was a dead end: nothing ever transitioned back out of it
                // once reached, so a script left running from idle never noticed new orders.
                if (config.stopWhenOrdersComplete()) {
                    log.info("GE Star V2: queue empty, stopping script.");
                    shutdown();
                } else if (queue.nextQueued().isPresent()) {
                    // A manual/foreign GE offer could have been placed while idling in DONE -
                    // reconcile once more before resubmitting so maxActiveOffers/slot counting
                    // accounts for it, same as the reconcile pass that runs right after Execute.
                    reconcileSubmittedOrders();
                    state = State.SUBMITTING_ORDERS;
                }
                break;
        }
    }

    private void prepareFundsOrItems() {
        if (orderAwaitingFunds == null) {
            state = State.SUBMITTING_ORDERS;
            return;
        }

        if (!config.withdrawFromBank()) {
            log.warn("GE Star V2: insufficient funds/items for {} and bank withdrawal is disabled, skipping.", orderAwaitingFunds);
            markSkipped(orderAwaitingFunds, "Insufficient funds/items, bank withdrawal disabled");
            orderAwaitingFunds = null;
            state = State.SUBMITTING_ORDERS;
            return;
        }

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) return;
            sleepUntil(Rs2Bank::isOpen);
        }

        if (orderAwaitingFunds.getAction() == GrandExchangeAction.BUY) {
            long needed = orderAwaitingFunds.totalValue() - Rs2Inventory.itemQuantity(ItemID.COINS);
            if (needed > 0) {
                Rs2Bank.withdrawX(ItemID.COINS, (int) needed);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        } else {
            int have = Rs2Inventory.itemQuantity(orderAwaitingFunds.getItemName());
            int needed = orderAwaitingFunds.getQuantity() - have;
            if (needed > 0) {
                Rs2Bank.withdrawX(orderAwaitingFunds.getItemName(), needed);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        orderAwaitingFunds = null;
        state = State.SUBMITTING_ORDERS;
    }

    private void submitNextOrder() {
        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.openExchange();
            return;
        }

        if (activeOrders.size() >= Math.max(1, config.maxActiveOffers())) {
            state = State.MONITORING_OFFERS;
            return;
        }

        Optional<GeStarOrder> next = queue.nextQueued();
        if (!next.isPresent()) {
            state = activeOrders.isEmpty() ? State.DONE : State.MONITORING_OFFERS;
            return;
        }
        GeStarOrder order = next.get();

        String rejection = guardrails.check(order);
        if (rejection != null) {
            log.warn("GE Star V2: rejected order [{}] - {}", order, rejection);
            markSkipped(order, rejection);
            if (config.stopOnGuardrailBreach()) {
                log.error("GE Star V2: stopping on guardrail breach as configured.");
                shutdown();
            }
            return;
        }

        if (!hasFundsOrItems(order)) {
            if (order == lastFundsShortfallOrder) {
                log.warn("GE Star V2: still short funds/items for {} after a bank visit, skipping.", order);
                markSkipped(order, "Insufficient funds/items after bank visit");
                lastFundsShortfallOrder = null;
                return;
            }
            lastFundsShortfallOrder = order;
            orderAwaitingFunds = order;
            state = State.PREPARING_FUNDS_OR_ITEMS;
            return;
        }
        lastFundsShortfallOrder = null;

        int submitPrice = clampToLivePrice(order);

        // NOTE: Rs2GrandExchange.buyItem and .sellItem have inconsistent parameter order with
        // each other - verified directly against the client jar's bytecode after a live bug
        // report of price/quantity being swapped. buyItem(name, price, quantity) but
        // sellItem(name, quantity, price). Do not "fix" this to look symmetric without
        // re-verifying against the jar - it really is asymmetric.
        GrandExchangeSlots slotBefore = Rs2GrandExchange.getAvailableSlot();
        boolean submitted = order.getAction() == GrandExchangeAction.BUY
            ? Rs2GrandExchange.buyItem(order.getItemName(), submitPrice, order.getQuantity())
            : Rs2GrandExchange.sellItem(order.getItemName(), order.getQuantity(), submitPrice);

        if (submitted) {
            if (order.getAction() == GrandExchangeAction.BUY) {
                guardrails.recordSpend((long) submitPrice * order.getQuantity());
            }
            GrandExchangeSlots slot = slotBefore != null ? slotBefore : Rs2GrandExchange.findSlotForItem(order.getItemName(), order.getAction() == GrandExchangeAction.BUY);
            order.setSlot(slot);
            order.setStatus(GeStarOrder.Status.SUBMITTED);
            queue.notifyChanged();
            if (slot != null) {
                activeOrders.put(slot, order);
            }
            log.info("GE Star V2: submitted {}", order);
        } else {
            log.warn("GE Star V2: failed to submit order {}, will retry next tick.", order);
        }
    }

    /**
     * Hard price cap enforced at the moment of submission, independent of the (softer,
     * rejects-rather-than-clamps) price-deviation guardrail: a BUY order is never actually
     * offered above the live insta-buy price, and a SELL order is never actually offered
     * below the live insta-sell price - so the plugin never pays more than the real market for
     * a buy, or accepts less than the real market for a sell, even if the order's queued price
     * (set when it was added, possibly stale by the time it's actually submitted) was higher/
     * lower. If live price data isn't available for any reason, falls back to the order's own
     * price unchanged rather than blocking submission on a missing lookup.
     *
     * <p>Uses {@link GeStarWikiPriceClient} (a direct call to the OSRS Wiki's real-time API),
     * not {@code Rs2GrandExchange.getRealTimePrices} - that Microbot Hub utility sources its
     * price from ge-tracker.com's API first (verified against its bytecode), a third-party
     * aggregator whose data caused a live-reported bad clamp (an order clamped down to ~10gp
     * for an item genuinely worth ~40gp). See GeStarWikiPriceClient's javadoc for the full
     * story - this is the one price lookup in the whole submission path that must not trust a
     * lagging/wrong source, since it's the last check before real GP moves.
     */
    private int clampToLivePrice(GeStarOrder order) {
        int itemId = itemManager.getItemId(order.getItemName());
        if (itemId <= 0) return order.getPrice();

        GeStarWikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null) return order.getPrice();

        if (order.getAction() == GrandExchangeAction.BUY) {
            if (price.instaBuyPrice <= 0) return order.getPrice();
            int capped = Math.min(order.getPrice(), price.instaBuyPrice);
            if (capped < order.getPrice()) {
                log.info("GE Star V2: capped buy price for {} from {} to live insta-buy price {}",
                    order.getItemName(), order.getPrice(), price.instaBuyPrice);
            }
            return capped;
        } else {
            if (price.instaSellPrice <= 0) return order.getPrice();
            int floored = Math.max(order.getPrice(), price.instaSellPrice);
            if (floored > order.getPrice()) {
                log.info("GE Star V2: raised sell price for {} from {} to live insta-sell price {}",
                    order.getItemName(), order.getPrice(), price.instaSellPrice);
            }
            return floored;
        }
    }

    private boolean hasFundsOrItems(GeStarOrder order) {
        if (order.getAction() == GrandExchangeAction.BUY) {
            return Rs2Inventory.itemQuantity(ItemID.COINS) >= order.totalValue();
        }
        return Rs2Inventory.itemQuantity(order.getItemName()) >= order.getQuantity();
    }

    private void monitorOffers() {
        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.openExchange();
            return;
        }

        // Auto-detect completed/partially-filled offers via the live offer details API
        // (backed by the same client state GrandExchangeOfferChanged notifies us of).
        for (Map.Entry<GrandExchangeSlots, GeStarOrder> entry : new LinkedHashMap<>(activeOrders).entrySet()) {
            GrandExchangeSlots slot = entry.getKey();
            GeStarOrder order = entry.getValue();

            GrandExchangeOfferDetails details = Rs2GrandExchange.getOfferDetails(slot);
            if (details == null) continue;

            int filled = order.getAction() == GrandExchangeAction.BUY
                ? Rs2GrandExchange.getItemsBoughtFromOffer(slot)
                : Rs2GrandExchange.getItemsSoldFromOffer(slot);
            order.setQuantityFilled(filled);

            GrandExchangeOfferState offerState = details.getState();
            boolean finished = offerState == GrandExchangeOfferState.BOUGHT
                || offerState == GrandExchangeOfferState.SOLD
                || offerState == GrandExchangeOfferState.CANCELLED_BUY
                || offerState == GrandExchangeOfferState.CANCELLED_SELL;

            if (finished) {
                log.info("GE Star V2: offer complete in slot {} - {} ({} filled)", slot, order, filled);
                recordCostBasis(order, details, filled);
                Rs2GrandExchange.collectOffer(slot, config.collectToBank());
                order.setStatus(GeStarOrder.Status.DONE);
                activeOrders.remove(slot);
            }
            queue.notifyChanged();
        }

        if (activeOrders.isEmpty() && !queue.nextQueued().isPresent()) {
            state = State.DONE;
        } else if (activeOrders.size() < Math.max(1, config.maxActiveOffers()) && queue.nextQueued().isPresent()) {
            state = State.SUBMITTING_ORDERS;
        }
    }

    /**
     * Records the actual filled quantity/GP against the portfolio's cost-basis ledger, using
     * GrandExchangeOfferDetails.getSpent() (the real GP that changed hands for this offer)
     * rather than order.getPrice() * filled - a partial fill or a fill at a different clearing
     * price than requested should cost-base at what actually happened, not what was asked for.
     */
    private void recordCostBasis(GeStarOrder order, GrandExchangeOfferDetails details, int filled) {
        if (filled <= 0) return;
        int itemId = details.getItemId();
        if (order.getAction() == GrandExchangeAction.BUY) {
            portfolio.recordBuy(itemId, filled, details.getSpent(), System.currentTimeMillis());
        } else {
            portfolio.recordSell(itemId, filled, details.getSpent());
        }
    }

    private void markSkipped(GeStarOrder order, String reason) {
        order.setStatus(GeStarOrder.Status.SKIPPED);
        order.setStatusDetail(reason);
        queue.notifyChanged();
    }

    /**
     * Called from the plugin's {@code @Subscribe} handler on every GrandExchangeOfferChanged
     * event. The polling in {@link #monitorOffers()} already reads live state each tick, so
     * this is just a low-cost log line confirming fills are being observed in real time rather
     * than only when we happen to poll.
     */
    public void onOfferChanged(GrandExchangeOfferChanged event) {
        if (event.getOffer() == null) return;
        GrandExchangeOfferState offerState = event.getOffer().getState();
        if (offerState == GrandExchangeOfferState.BOUGHT || offerState == GrandExchangeOfferState.SOLD) {
            log.info("GE Star V2: detected fill in slot {} - {} of {}, state {}",
                event.getSlot(), event.getOffer().getQuantitySold(), event.getOffer().getTotalQuantity(), offerState);
        }
    }

    public int getActiveOfferCount() {
        return activeOrders.size();
    }

    public long getGpSpentThisSession() {
        return guardrails == null ? 0 : guardrails.getGpSpentThisSession();
    }

    public State getState() {
        return state;
    }
}
