package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;
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

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * State machine that pulls queued orders from the shared {@link OrderQueue} (the same queue the
 * sidebar panel and right-click menu add to), submits offers through {@link Rs2GrandExchange},
 * and updates each order's live status/fill as it progresses. {@link Guardrails} are checked
 * immediately before every submission.
 *
 * <p>Milestone 1 scope (see PROPOSAL.md §5): this is manual-order-execution only. "What to
 * submit next" comes purely from {@link OrderQueue#nextQueued()} - there is no DECIDE/PPO-
 * calling phase in this state machine yet, so the loop here is deliberately
 * IDLE → GOING_TO_GE → SUBMITTING_ORDERS → MONITORING_OFFERS → COLLECTING(via monitor)/DONE
 * rather than the eventual IDLE → GOING_TO_GE → OBSERVE → DECIDE → SUBMITTING → MONITORING →
 * COLLECTING loop the proposal describes for when autonomous decision-making lands.
 */
@Slf4j
public class PPOFlipperStarScript extends Script {

    private static final int SCHEDULE_INTERVAL_MS = 600;

    enum State {
        IDLE,
        GOING_TO_GE,
        PREPARING_FUNDS_OR_ITEMS,
        SUBMITTING_ORDERS,
        MONITORING_OFFERS,
        COLLECTING,
        CANCELLING_ALL,
        DONE
    }

    private final OrderQueue queue;
    private final PortfolioManager portfolio;
    private final BuyLimitLedger buyLimitLedger;
    private final GoldManager goldManager;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final WikiPriceClient wikiPriceClient = new WikiPriceClient();

    private PPOFlipperStarConfig config;
    private Guardrails guardrails;

    private State state = State.IDLE;
    private final Map<GrandExchangeSlots, PPOFlipperOrder> activeOrders = new LinkedHashMap<>();
    private PPOFlipperOrder orderAwaitingFunds;
    private PPOFlipperOrder lastFundsShortfallOrder;

    // True right after Execute, until the first reconcile pass has run once the GE is open -
    // see reconcileSubmittedOrders' javadoc for why this matters (Stop never cancels real
    // in-game offers, only this script's own loop).
    private boolean needsReconcile = false;

    // Set by requestCancelAll() (the panel's "Cancel all offers" button), read and cleared at
    // the top of tick() - pre-empts whatever the script was doing. Volatile since it's set from
    // the EDT (button click) and read from this script's own scheduled-executor thread.
    private volatile boolean cancelAllRequested = false;

    @Inject
    public PPOFlipperStarScript(OrderQueue queue, PortfolioManager portfolio, BuyLimitLedger buyLimitLedger,
                                 GoldManager goldManager, PPOFlipperStarFirestoreSync firestoreSync) {
        this.queue = queue;
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.goldManager = goldManager;
        this.firestoreSync = firestoreSync;
    }

    public boolean run(PPOFlipperStarConfig config) {
        this.config = config;
        this.guardrails = new Guardrails(config, portfolio, buyLimitLedger, queue);
        this.guardrails.reset();
        this.state = State.GOING_TO_GE;
        this.activeOrders.clear();
        this.orderAwaitingFunds = null;
        this.lastFundsShortfallOrder = null;
        this.needsReconcile = true;

        if (!goldManager.hasSessionSnapshot()) {
            goldManager.snapshotSessionStart();
        }

        Rs2AntibanSettings.naturalMouse = true;
        Rs2Antiban.setActivityIntensity(ActivityIntensity.LOW);

        long intervalMs = Math.max(1, config.decisionTickIntervalSeconds()) * 1000L;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                tick();
            } catch (Exception ex) {
                log.error("PPOFlipperStar: error in script loop: {} - ", ex.getMessage(), ex);
            }
        }, 0, Math.min(intervalMs, SCHEDULE_INTERVAL_MS), TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Requests that every currently active GE offer be aborted and collected back to
     * inventory/bank on this script's next tick(s), regardless of what it's currently doing -
     * pre-empts normal order submission/monitoring the same tick it's noticed, and starts the
     * script's own scheduled loop first if it isn't already running (so this works as a
     * standalone panic button from the panel even with Execute never clicked). Does not touch
     * OrderQueue itself - the panel clears QUEUED orders separately once this completes.
     */
    public void requestCancelAll(PPOFlipperStarConfig config) {
        if (!isRunning()) {
            run(config);
        } else {
            this.config = config;
        }
        cancelAllRequested = true;
    }

    /** True while a cancel-all is in progress (requested but not yet finished). */
    public boolean isCancellingAll() {
        return state == State.CANCELLING_ALL;
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
     * Stopping the script never cancels real in-game offers - only the previous run's in-memory
     * {@code activeOrders} map is lost - so every order still marked SUBMITTED from before needs
     * to be checked against what's actually live on the GE right now, rather than assumed
     * orphaned. A SUBMITTED order that matches a live offer gets its slot restored into
     * activeOrders (monitoring picks it back up exactly where it left off); only orders with no
     * matching live offer get reset back to QUEUED for resubmission.
     *
     * <p>Also adopts any live GE slot that isn't accounted for by the queue at all - an offer
     * placed manually, by another tool, or left over from before this plugin was ever run.
     */
    private void reconcileSubmittedOrders() {
        List<PPOFlipperOrder> submittedOrders = new ArrayList<>(queue.getByStatus(PPOFlipperOrder.Status.SUBMITTED));

        for (GrandExchangeSlots slot : Rs2GrandExchange.getActiveOfferSlots()) {
            GrandExchangeOfferDetails details = Rs2GrandExchange.getOfferDetails(slot);
            if (details == null) continue;

            GrandExchangeAction liveAction = details.isSelling() ? GrandExchangeAction.SELL : GrandExchangeAction.BUY;

            PPOFlipperOrder match = submittedOrders.stream()
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
                log.info("PPOFlipperStar: reconciled SUBMITTED order {} to live slot {}", match, slot);
            } else {
                int itemId = itemManager.getItemId(details.getItemName());
                PPOFlipperOrder adopted = new PPOFlipperOrder(liveAction, itemId, details.getItemName(), details.getTotalQuantity(), details.getPrice());
                adopted.setSlot(slot);
                adopted.setStatus(PPOFlipperOrder.Status.SUBMITTED);
                adopted.setQuantityFilled(liveAction == GrandExchangeAction.BUY
                    ? Rs2GrandExchange.getItemsBoughtFromOffer(slot)
                    : Rs2GrandExchange.getItemsSoldFromOffer(slot));
                queue.add(adopted);
                activeOrders.put(slot, adopted);
                log.info("PPOFlipperStar: adopted untracked live offer in slot {} - {}", slot, adopted);
            }
        }

        int orphaned = 0;
        for (PPOFlipperOrder order : submittedOrders) {
            if (!activeOrders.containsValue(order)) {
                order.setStatus(PPOFlipperOrder.Status.QUEUED);
                orphaned++;
            }
        }

        if (orphaned > 0) {
            log.warn("PPOFlipperStar: {} SUBMITTED order(s) had no matching live GE offer, re-queued for resubmission", orphaned);
        }
        queue.notifyChanged();
    }

    /**
     * Walks to/opens the GE if needed, then aborts every active offer and collects whatever
     * comes back (unfilled items/GP, and anything that had already finished) to inventory or
     * bank per {@code collectToBank}, via {@link Rs2GrandExchange#abortAllOffers}. Every order
     * this script had SUBMITTED is marked FAILED with an explanatory reason.
     */
    private void cancelAllOffers() {
        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.walkToGrandExchange();
            if (!Rs2GrandExchange.openExchange()) {
                return;
            }
        }

        List<PPOFlipperOrder> cancelled = new ArrayList<>(activeOrders.values());
        boolean allEmpty = Rs2GrandExchange.abortAllOffers(config.collectToBank());

        for (PPOFlipperOrder order : cancelled) {
            order.setStatus(PPOFlipperOrder.Status.FAILED);
            order.setStatusDetail("Cancelled - all offers pulled from the GE");
        }
        activeOrders.clear();
        orderAwaitingFunds = null;
        lastFundsShortfallOrder = null;
        queue.notifyChanged();

        if (!allEmpty) {
            log.warn("PPOFlipperStar: cancel-all did not leave every GE slot empty, will retry next tick.");
            return;
        }

        log.info("PPOFlipperStar: cancel-all complete - {} offer(s) pulled and collected.", cancelled.size());
        state = State.DONE;
    }

    private void tick() {
        if (cancelAllRequested && state != State.CANCELLING_ALL) {
            cancelAllRequested = false;
            state = State.CANCELLING_ALL;
        }

        switch (state) {
            case IDLE:
                break;

            case CANCELLING_ALL:
                cancelAllOffers();
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
            case COLLECTING:
                monitorOffers();
                break;

            case DONE:
                // Stay in DONE, idling on this same fixed-delay tick loop, and re-check the
                // queue every tick so an order added later (e.g. via right-click, long after
                // this script reached DONE) gets picked up without needing Execute clicked
                // again.
                if (queue.nextQueued().isPresent()) {
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
            log.warn("PPOFlipperStar: insufficient funds/items for {} and bank withdrawal is disabled, skipping.", orderAwaitingFunds);
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
        // Holds submission (retried next tick, not a terminal state) while the startup Firestore
        // reconcile is still in flight - see PPOFlipperStarFirestoreSync.reconcilePending's
        // javadoc for why trading against not-yet-reconciled local state is unsafe. Bounded by
        // that pull's own network timeout, not a separate wait here.
        if (firestoreSync.isReconcilePending()) {
            return;
        }

        if (!Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.openExchange();
            return;
        }

        if (activeOrders.size() >= Math.max(1, config.maxActiveOffers())) {
            state = State.MONITORING_OFFERS;
            return;
        }

        Optional<PPOFlipperOrder> next = queue.nextQueued();
        if (!next.isPresent()) {
            state = activeOrders.isEmpty() ? State.DONE : State.MONITORING_OFFERS;
            return;
        }
        PPOFlipperOrder order = next.get();

        String rejection = guardrails.check(order);
        if (rejection != null) {
            log.warn("PPOFlipperStar: rejected order [{}] - {}", order, rejection);
            markSkipped(order, rejection);
            if (config.stopOnGuardrailBreach()) {
                log.error("PPOFlipperStar: stopping on guardrail breach as configured.");
                shutdown();
            }
            return;
        }

        if (!hasFundsOrItems(order)) {
            if (order == lastFundsShortfallOrder) {
                log.warn("PPOFlipperStar: still short funds/items for {} after a bank visit, skipping.", order);
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

        // Cleared before recomputing so a clamp note from an earlier submit attempt (e.g. before
        // an orphan-requeue - see reconcileSubmittedOrders) doesn't linger and misdescribe this
        // attempt if this retry doesn't clamp.
        order.setStatusDetail(null);
        int submitPrice = clampToLivePrice(order);

        // NOTE: Rs2GrandExchange.buyItem and .sellItem have inconsistent parameter order with
        // each other - buyItem(name, price, quantity) but sellItem(name, quantity, price). Do
        // not "fix" this to look symmetric without re-verifying against the client jar - it
        // really is asymmetric (confirmed against microbot-2.6.21.jar's own method signatures).
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
            order.setSubmittedPrice(submitPrice);
            order.setStatus(PPOFlipperOrder.Status.SUBMITTED);
            queue.notifyChanged();
            if (slot != null) {
                activeOrders.put(slot, order);
            }
            log.info("PPOFlipperStar: submitted {}", order);
        } else {
            log.warn("PPOFlipperStar: failed to submit order {}, will retry next tick.", order);
        }
    }

    /**
     * Hard price cap enforced at the moment of submission, independent of the (softer,
     * rejects-rather-than-clamps) price-deviation guardrail: a BUY order is never actually
     * offered above the live insta-buy price, and a SELL order is never actually offered below
     * the live insta-sell price. Uses {@link WikiPriceClient} (a direct call to the OSRS Wiki's
     * real-time API), never {@code Rs2GrandExchange.getRealTimePrices} - see that client's
     * javadoc. If live price data isn't available for any reason, falls back to the order's own
     * price unchanged rather than blocking submission on a missing lookup.
     */
    private int clampToLivePrice(PPOFlipperOrder order) {
        int itemId = order.getItemId() > 0 ? order.getItemId() : itemManager.getItemId(order.getItemName());
        if (itemId <= 0) return order.getPrice();

        WikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null) return order.getPrice();

        if (order.getAction() == GrandExchangeAction.BUY) {
            if (price.instaBuyPrice <= 0) return order.getPrice();
            int capped = Math.min(order.getPrice(), price.instaBuyPrice);
            if (capped < order.getPrice()) {
                log.info("PPOFlipperStar: capped buy price for {} from {} to live insta-buy price {}",
                    order.getItemName(), order.getPrice(), price.instaBuyPrice);
                // Visible in the panel, not just the log - a human who typed a specific price
                // (the only source of orders in this milestone) should be able to see why the
                // fill price didn't match what they asked for without digging into logs.
                order.setStatusDetail(String.format(
                    "Price capped to live insta-buy %d gp (requested %d gp)", capped, order.getPrice()));
            }
            return capped;
        } else {
            if (price.instaSellPrice <= 0) return order.getPrice();
            int floored = Math.max(order.getPrice(), price.instaSellPrice);
            if (floored > order.getPrice()) {
                log.info("PPOFlipperStar: raised sell price for {} from {} to live insta-sell price {}",
                    order.getItemName(), order.getPrice(), price.instaSellPrice);
                order.setStatusDetail(String.format(
                    "Price raised to live insta-sell %d gp (requested %d gp)", floored, order.getPrice()));
            }
            return floored;
        }
    }

    private boolean hasFundsOrItems(PPOFlipperOrder order) {
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

        // Auto-detect completed/partially-filled offers via the live offer details API (backed
        // by the same client state GrandExchangeOfferChanged notifies us of).
        for (Map.Entry<GrandExchangeSlots, PPOFlipperOrder> entry : new LinkedHashMap<>(activeOrders).entrySet()) {
            GrandExchangeSlots slot = entry.getKey();
            PPOFlipperOrder order = entry.getValue();

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
                log.info("PPOFlipperStar: offer complete in slot {} - {} ({} filled)", slot, order, filled);
                state = State.COLLECTING;
                boolean collected = Rs2GrandExchange.collectOffer(slot, config.collectToBank());
                if (collected) {
                    // Cost-basis is only recorded once the GP/items have actually landed in
                    // inventory (or bank) - recording it before a successful collect would
                    // credit the ledger for something not actually held yet.
                    recordCostBasis(order, details, filled);
                    order.setStatus(PPOFlipperOrder.Status.DONE);
                    activeOrders.remove(slot);
                } else {
                    // Collection failed (GE widget not ready/interactable this tick, a
                    // transient timing issue). Leave the order SUBMITTED and in activeOrders so
                    // the next tick's monitorOffers pass retries it.
                    log.warn("PPOFlipperStar: collectOffer failed for slot {} - {}, will retry next tick", slot, order);
                }
            }
            queue.notifyChanged();
        }

        if (activeOrders.isEmpty() && !queue.nextQueued().isPresent()) {
            state = State.DONE;
        } else if (activeOrders.size() < Math.max(1, config.maxActiveOffers()) && queue.nextQueued().isPresent()) {
            state = State.SUBMITTING_ORDERS;
        } else if (!activeOrders.isEmpty()) {
            state = State.MONITORING_OFFERS;
        }
    }

    /**
     * Records the actual filled quantity/GP against the portfolio's cost-basis ledger, using
     * GrandExchangeOfferDetails.getSpent() (the real GP that changed hands for this offer)
     * rather than order.getPrice() * filled - a partial fill or a fill at a different clearing
     * price than requested should cost-base at what actually happened, not what was asked for.
     */
    private void recordCostBasis(PPOFlipperOrder order, GrandExchangeOfferDetails details, int filled) {
        if (filled <= 0) return;
        int itemId = details.getItemId();
        long now = System.currentTimeMillis();
        if (order.getAction() == GrandExchangeAction.BUY) {
            portfolio.recordBuy(itemId, filled, details.getSpent(), now);
            buyLimitLedger.recordBuy(itemId, filled, now);
        } else {
            portfolio.recordSell(itemId, filled, details.getSpent());
        }
        recordTradeHistory(order, details, filled, now);
    }

    /**
     * Appends one immutable trade-history record to Firestore for this completed fill (see
     * PROPOSAL.md's Firestore-persistence addendum: a new, additive collection - nothing tracked
     * this as its own history locally before). Uses {@code order.getSubmittedPrice()} (the
     * actual price offered to the GE, per-unit) rather than {@code order.getPrice()} (what was
     * originally requested) for pricePerUnit, and {@code details.getSpent()} (the real GP that
     * changed hands for this offer, same source {@link #recordCostBasis} itself uses) for
     * totalGp - a partial fill or a fill at a different clearing price than requested should be
     * logged at what actually happened, not what was asked for. Best-effort/no-op if cloud sync
     * is disabled - this never blocks or affects local state.
     */
    private void recordTradeHistory(PPOFlipperOrder order, GrandExchangeOfferDetails details, int filled, long timestampMillis) {
        if (!firestoreSync.isEnabled()) return;
        int pricePerUnit = order.getSubmittedPrice() > 0 ? order.getSubmittedPrice() : order.getPrice();
        firestoreSync.pushTradeHistoryAsync(order.getAction().name(), details.getItemId(), order.getItemName(),
            filled, pricePerUnit, details.getSpent(), timestampMillis);
    }

    private void markSkipped(PPOFlipperOrder order, String reason) {
        order.setStatus(PPOFlipperOrder.Status.SKIPPED);
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
            log.info("PPOFlipperStar: detected fill in slot {} - {} of {}, state {}",
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
