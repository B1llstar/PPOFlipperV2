package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.CostBasisEntry;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreClient;
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
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * State machine that pulls queued orders from the shared {@link OrderQueue} (the same queue the
 * sidebar panel and right-click menu add to), submits offers through {@link Rs2GrandExchange},
 * and updates each order's live status/fill as it progresses. {@link Guardrails} are checked
 * immediately before every submission.
 *
 * <p>Milestone 1 scope (see PROPOSAL.md §5) was manual-order-execution only, with "what to
 * submit next" coming purely from {@link OrderQueue#nextQueued()}. Milestone 4 (this) adds a
 * DECIDE phase, per §2.4/§3.6: on a separate cadence from the mechanical execution loop below, the
 * script builds a state vector for every watchlisted item via {@link DecisionEngine}, sends it to
 * the Firestore-listening Python inference worker, and waits (bounded, defaulting to a no-op on
 * timeout) for its proposed actions. See {@link #runDecideTick()}'s javadoc for the shadow-mode
 * guarantee this phase operates under - it is additive to, and never replaces, manual ordering via
 * OrderQueue.nextQueued() below, which is unchanged from milestone 1.
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
    private final DecisionEngine decisionEngine;
    private final DecisionSuggestions decisionSuggestions;
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

    // DECIDE phase runs on its own cadence (decisionTickIntervalSeconds), independent of the
    // mechanical execution tick loop's own SCHEDULE_INTERVAL_MS - this just tracks when the next
    // decide-tick is due, read/written only from this script's own scheduled-executor thread.
    private long nextDecisionTickAtMillis = 0;

    // Proactive bank refresh runs on its own cadence (bankRefreshIntervalSeconds), independent of
    // everything else - see maybeRefreshBank()'s javadoc for why this exists at all. 0 means "due
    // immediately" so the very first tick after Execute always gets one refresh in regardless of
    // the configured interval, rather than waiting a full interval before the bank-held portion of
    // holdings is trustworthy for the first time.
    private long nextBankRefreshAtMillis = 0;

    // True only for the exact duration this script is itself deliberately driving the bank
    // interface (maybeRefreshBank's read-only open/close, or prepareFundsOrItems' withdrawal) -
    // see guardAgainstUnexpectedBank()'s javadoc. This script's own bank use is ALWAYS either a
    // pure read (refresh) or a withdrawal, NEVER a deposit - so the bank ever being open while
    // this flag is false is unexpected by definition, regardless of the actual cause (another
    // plugin, a misclick, a keybind), and is closed on sight as a pure precaution.
    private volatile boolean intentionalBankUseActive = false;

    // Fixed cadence (not user-configurable - see maybeSyncLiveHoldings' javadoc) for pushing real
    // live holdings to Firestore, independent of bankRefreshIntervalSeconds so this still runs
    // for a user with inventory-only mode on or bank refresh disabled.
    private static final long LIVE_HOLDINGS_SYNC_INTERVAL_MILLIS = 60_000L;
    private long nextLiveHoldingsSyncAtMillis = 0;

    // Last time a BUY suggestion for this item id was surfaced (shown in the panel, or
    // auto-submitted) - see maybeApplyBuyCooldown()'s javadoc for why this exists. Keyed by item
    // id, never touched for SELL suggestions. Read/written only from the DECIDE executor thread.
    private final Map<Integer, Long> lastBuySuggestionAtMillis = new HashMap<>();

    // Last time an item+action combo was REJECTED by Guardrails while being autonomously
    // submitted - see passesRejectionCooldown's javadoc for why this exists (a real incident: the
    // same guardrail-rejected item/action was being re-proposed and re-rejected every DECIDE
    // tick, sometimes multiple times a minute, because nothing remembered "this was just tried
    // and failed"). Keyed by item id + action, applies to both BUY and SELL (unlike
    // lastBuySuggestionAtMillis above, which is a different, BUY-only mechanism for a different
    // problem - see its own javadoc). Deliberately scoped to autonomous submission only - a
    // MANUALLY queued order that gets rejected should still be retried/reconsidered by the human
    // who queued it without this plugin silently sitting on it. Read/written only from the DECIDE
    // executor thread (autonomouslySubmit checks it before queuing; submitNextOrder records into
    // it only for orders that originated autonomously - see markSkipped's call sites).
    private final Map<String, Long> lastAutonomousRejectionAtMillis = new HashMap<>();

    // A single-thread executor DECIDE ticks run on, kept separate from the main tick loop's
    // scheduledExecutorService so a slow/blocked Firestore round-trip (bounded by
    // decisionResponseTimeoutSeconds, but still up to several seconds) never delays order
    // submission/monitoring. (Re)created in run(), shut down in shutdown().
    private ExecutorService decideExecutor;
    private final AtomicBoolean decideInFlight = new AtomicBoolean(false);

    // Counts consecutive DecisionEngine timeouts (the model not responding at all, per
    // DecisionEngine#didLastDecideTimeOut's javadoc) - reset to 0 the moment a real response
    // comes back. Read by PPOFlipperStarPanel via isModelUnresponsive() to show a visible warning
    // instead of only a log line - a real incident (the Python inference worker got killed and
    // never restarted) left this plugin silently defaulting every tick to HOLD with nothing in
    // the UI to notice it by. MODEL_UNRESPONSIVE_THRESHOLD ticks must fail in a row before the
    // warning shows, so a single transient network hiccup doesn't flash a scary warning for no
    // reason - only a genuinely stuck/dead worker does.
    private static final int MODEL_UNRESPONSIVE_THRESHOLD = 4;
    private final AtomicInteger consecutiveDecideTimeouts = new AtomicInteger(0);

    private final DecideDiagnosticsLog diagnosticsLog;

    @Inject
    public PPOFlipperStarScript(OrderQueue queue, PortfolioManager portfolio, BuyLimitLedger buyLimitLedger,
                                 GoldManager goldManager, PPOFlipperStarFirestoreSync firestoreSync,
                                 DecisionEngine decisionEngine, DecisionSuggestions decisionSuggestions,
                                 DecideDiagnosticsLog diagnosticsLog) {
        this.queue = queue;
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.goldManager = goldManager;
        this.firestoreSync = firestoreSync;
        this.decisionEngine = decisionEngine;
        this.decisionSuggestions = decisionSuggestions;
        this.diagnosticsLog = diagnosticsLog;
    }

    public boolean run(PPOFlipperStarConfig config) {
        this.config = config;
        this.guardrails = new Guardrails(config, portfolio, buyLimitLedger, queue, decisionEngine);
        this.guardrails.reset();
        this.state = State.GOING_TO_GE;
        this.activeOrders.clear();
        this.orderAwaitingFunds = null;
        this.lastFundsShortfallOrder = null;
        this.needsReconcile = true;
        this.nextDecisionTickAtMillis = 0;
        this.nextBankRefreshAtMillis = 0;
        this.nextLiveHoldingsSyncAtMillis = 0;
        this.lastBuySuggestionAtMillis.clear();
        this.lastAutonomousRejectionAtMillis.clear();
        this.decideInFlight.set(false);
        this.consecutiveDecideTimeouts.set(0);
        if (this.decideExecutor != null) {
            this.decideExecutor.shutdownNow();
        }
        this.decideExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PPOFlipperStar-Decide");
            t.setDaemon(true);
            return t;
        });

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

    /**
     * True once {@link #MODEL_UNRESPONSIVE_THRESHOLD} or more DECIDE ticks in a row have timed
     * out waiting for a {@code decision/response} - see {@code consecutiveDecideTimeouts}' javadoc
     * for the incident this exists to make visible. Read by {@code PPOFlipperStarPanel} to show a
     * warning; not itself a behavior change - the script already handled a timeout safely
     * (defaulting to HOLD) before this existed, this only makes a stuck/dead Python inference
     * worker obvious in the UI instead of only in the log.
     */
    public boolean isModelUnresponsive() {
        return consecutiveDecideTimeouts.get() >= MODEL_UNRESPONSIVE_THRESHOLD;
    }

    @Override
    public void shutdown() {
        activeOrders.clear();
        orderAwaitingFunds = null;
        lastFundsShortfallOrder = null;
        state = State.IDLE;
        if (decideExecutor != null) {
            decideExecutor.shutdownNow();
            decideExecutor = null;
        }
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
                // getItemIdByName(name, true) does an exact (equalsIgnoreCase) match, unlike
                // getItemId(String)'s plain substring search - see PPOFlipperStarPanel's
                // onAddOrderClicked javadoc for the real "Pie dish" vs "Unfired pie dish" bug this
                // avoids.
                int itemId = Rs2ItemManager.getItemIdByName(details.getItemName(), true);
                PPOFlipperOrder adopted = new PPOFlipperOrder(liveAction, itemId, details.getItemName(), details.getTotalQuantity(), details.getPrice());
                adopted.setSlot(slot);
                adopted.setStatus(PPOFlipperOrder.Status.SUBMITTED);
                // Approximation: an adopted offer's real GE submission time is unrecoverable (it
                // predates this plugin ever knowing about it - left over from a previous session,
                // another tool, or a manual placement). Stamping "now" understates its true age
                // for staleness purposes (checkStaleOffers), but there's no live-offer API that
                // exposes actual placement time - starting its staleness clock now, rather than
                // never, is the safer failure mode (an old offer just needs one extra staleness-
                // window's worth of ticks before this catches up to it).
                adopted.setSubmittedAtMillis(System.currentTimeMillis());
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
        guardAgainstUnexpectedBank();

        if (cancelAllRequested && state != State.CANCELLING_ALL) {
            cancelAllRequested = false;
            state = State.CANCELLING_ALL;
        }

        maybeRunDecideTick();
        maybeRefreshBank();
        maybeSyncLiveHoldings();

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

    /**
     * Fires {@link #runDecideTick()} on its own cadence ({@code decisionTickIntervalSeconds},
     * independent of the mechanical execution loop's own faster {@link #SCHEDULE_INTERVAL_MS}
     * poll rate) - checked every tick() call but only actually dispatches work once the interval
     * has elapsed. Dispatched onto {@link #decideExecutor} rather than run inline: DECIDE's
     * Firestore write-then-poll round-trip (bounded by {@code decisionResponseTimeoutSeconds},
     * up to several seconds) must never delay order submission/monitoring on this same tick
     * loop's thread - see {@link #runDecideTick()}'s javadoc for the full flow.
     */
    private void maybeRunDecideTick() {
        long now = System.currentTimeMillis();
        if (now < nextDecisionTickAtMillis) return;

        long intervalMs = Math.max(1, config.decisionTickIntervalSeconds()) * 1000L;
        nextDecisionTickAtMillis = now + intervalMs;

        if (decideExecutor == null || decideExecutor.isShutdown()) return;
        if (!decideInFlight.compareAndSet(false, true)) {
            // A previous decide-tick's Firestore round-trip is still in flight (e.g. this tick's
            // interval elapsed again before the last poll timed out) - skip dispatching another
            // one on top of it rather than piling up concurrent DECIDE calls against the same
            // account's single decision/request document.
            return;
        }

        final PPOFlipperStarConfig configSnapshot = config;
        try {
            decideExecutor.execute(() -> {
                try {
                    runDecideTick(configSnapshot);
                } catch (Exception e) {
                    log.warn("PPOFlipperStar: DECIDE tick failed unexpectedly - {}", e.getMessage(), e);
                } finally {
                    lastDecideTickCompletedAtMillis = System.currentTimeMillis();
                    decideInFlight.set(false);
                }
            });
        } catch (Exception e) {
            decideInFlight.set(false);
        }
    }

    // Wall-clock time the most recent DECIDE tick actually finished running (success or a clean
    // failure) - purely observational bookkeeping for the panel's "Last DECIDE tick" status row,
    // does not affect DECIDE behavior at all.
    private volatile long lastDecideTickCompletedAtMillis = 0;

    /** Milliseconds since the most recent DECIDE tick completed, or 0 if none has completed yet this session. */
    public long millisSinceLastDecideTickCompleted() {
        return lastDecideTickCompletedAtMillis == 0 ? 0 : System.currentTimeMillis() - lastDecideTickCompletedAtMillis;
    }

    /** The configured DECIDE tick cadence in seconds - used by the panel to judge how stale millisSinceLastDecideTickCompleted() is. */
    public int getDecisionTickIntervalSeconds() {
        return config != null ? Math.max(1, config.decisionTickIntervalSeconds()) : 1;
    }

    /**
     * Proactively opens and immediately closes the bank on {@code bankRefreshIntervalSeconds}, for
     * no reason other than to populate/refresh {@code Rs2Bank}'s cache - purely a read, never a
     * withdrawal or deposit. This exists because {@link PortfolioManager#getHeldQuantity} and
     * {@link PortfolioManager#getAllHoldings} (used by the panel's portfolio display,
     * {@link Guardrails}, and {@link DecisionEngine}'s state vector) read that cache directly, and
     * it is <b>only ever populated reactively once the bank has actually been opened this
     * session</b> - see {@link BankManager}'s javadoc. Without this, anything held in the bank
     * silently reads as 0 for the entire session until something else happens to open the bank
     * first (e.g. {@link #prepareFundsOrItems()} mid-withdrawal) - which is exactly backwards,
     * since that withdrawal path itself only ever triggers off an order that was never generated
     * in the first place, because the model/guardrails already believed the item wasn't held.
     *
     * <p>Deliberately does nothing beyond open-then-close: this method must never withdraw,
     * deposit, or otherwise decide what to do with bank contents - refreshing the holdings number
     * used for display/guardrails is a completely separate concern from deciding what to sell,
     * which stays gated entirely by {@link WatchlistManager} (see {@link DecisionEngine}). Holding
     * an item in the bank does not, by itself, make this plugin try to sell it.
     *
     * <p>No-ops entirely when {@code bankRefreshIntervalSeconds} is 0 (the default) or
     * {@code inventoryOnlyMode} is on (bank contents aren't consulted at all in that mode, so
     * refreshing them would be pure overhead), and skips a cycle if the bank happens to already be
     * open for another reason (e.g. {@link #prepareFundsOrItems()} mid-withdrawal) rather than
     * interfering with it.
     */
    /**
     * Precaution added after a real incident: the bank interface was observed open with a
     * "deposit all" already executed against this script's held inventory, with no code path in
     * this class ever calling anything but withdraw - see {@link #intentionalBankUseActive}'s
     * javadoc. The actual trigger was never conclusively identified (ruled out: no other
     * deposit-all-capable plugin was enabled at the time), so this is a blind precaution, not a
     * fix for a known root cause - it can't distinguish what opened the bank, only that THIS
     * script didn't, and closes it unconditionally on sight rather than trying to inspect intent
     * further (by the time anything could be inspected, e.g. a shrinking inventory count, a
     * deposit may have already completed).
     *
     * <p>Deliberately closes immediately rather than waiting to see whether inventory actually
     * shrinks first - a "wait and confirm" approach reacts one tick too late by definition, after
     * whatever deposit/withdraw already happened. Closing a bank that some OTHER legitimate actor
     * (the player manually banking, another plugin doing real work) opened is an acceptable false-
     * positive cost for guaranteeing this script's own holdings are never silently deposited away
     * without its knowledge.
     *
     * <p>Called first thing in {@link #tick()}, before anything else - if this script itself is
     * about to legitimately open the bank this same tick (refresh or withdrawal), it sets
     * {@link #intentionalBankUseActive} true around that specific window, so this check passes
     * through harmlessly during its own bank use.
     */
    private void guardAgainstUnexpectedBank() {
        if (!config.guardAgainstUnexpectedBank()) return;
        if (intentionalBankUseActive) return;
        if (!Rs2Bank.isOpen()) return;

        log.warn("PPOFlipperStar: bank interface is open but this script did not open it - closing it " +
            "immediately as a precaution against an unexpected deposit/withdrawal (see guardAgainstUnexpectedBank's " +
            "javadoc for the real incident this guards against).");
        Rs2Bank.closeBank();
    }

    private void maybeRefreshBank() {
        if (config.inventoryOnlyMode()) return;
        int intervalSeconds = config.bankRefreshIntervalSeconds();
        if (intervalSeconds <= 0) return;
        if (!Rs2GrandExchange.isOpen()) return;

        long now = System.currentTimeMillis();
        if (now < nextBankRefreshAtMillis) return;
        nextBankRefreshAtMillis = now + (intervalSeconds * 1000L);

        log.info("PPOFlipperStar: bank refresh due (GE open: {}, Rs2Bank.isOpen(): {})",
            Rs2GrandExchange.isOpen(), Rs2Bank.isOpen());
        if (Rs2Bank.isOpen()) {
            log.warn("PPOFlipperStar: proactive bank refresh skipped - Rs2Bank.isOpen() reports true while " +
                "this script never opened it, which would prevent any refresh from ever happening if this is a " +
                "stale/incorrect cached state rather than a genuinely open bank interface.");
            return;
        }

        // Diagnostic logging added after a real incident: openBank() was silently returning false
        // every 30s for an entire session (confirmed via bytecode research - it can fail for
        // several reasons: no bank/GE-booth object found within Rs2GameObject's 20-tile scan, an
        // out-of-range clickObject triggering a walk and returning false for that attempt, the
        // bank interface not opening within its internal 5s timeout, or an exception mid-call) -
        // with no way to tell which, the bank-held portion of holdings silently stayed stale for
        // the entire session (see Guardrails/PortfolioManager - a real item held only in the bank,
        // like a confirmed-in-bank Pie dish, read back as 0 held and had its SELL rejected). This
        // log line exists so a repeat is diagnosable from client.log instead of invisible.
        intentionalBankUseActive = true;
        try {
            boolean opened = Rs2Bank.openBank();
            if (!opened) {
                log.warn("PPOFlipperStar: proactive bank refresh failed - Rs2Bank.openBank() returned false " +
                    "(GE open: {}, near GE: unknown - see openBank's own internal logging for the specific cause).",
                    Rs2GrandExchange.isOpen());
                return;
            }
            boolean actuallyOpen = sleepUntil(Rs2Bank::isOpen);
            if (!actuallyOpen) {
                log.warn("PPOFlipperStar: proactive bank refresh failed - openBank() returned true but the bank " +
                    "interface never actually opened within the wait.");
                return;
            }
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen());
        } finally {
            intentionalBankUseActive = false;
        }
    }

    /**
     * Pushes real live holdings (inventory + bank) to Firestore on a fixed cadence, so the web
     * dashboard reflects actual current stock rather than only what {@link PortfolioManager}'s
     * own cost-basis ledger has recorded through a completed trade. See
     * {@link PortfolioManager#pushLiveHoldingsToFirestore}'s javadoc for the full "why" - this was
     * a real gap where physical bank/inventory stock that predated this ledger (or was never
     * bought through this plugin) never reached Firestore and never showed up on the dashboard,
     * even though the same live read correctly informed local guardrail checks.
     *
     * <p>Deliberately its own fixed-interval timer, not reusing {@code bankRefreshIntervalSeconds}
     * - this should keep running even for a user with inventory-only mode on or bank refresh
     * disabled, since inventory-only holdings still deserve to reach the dashboard. Not user-
     * configurable: unlike the bank refresh (which has a real cost - opening/closing the bank
     * interface in-game), this is a cheap Firestore write with no in-game action, so there's no
     * meaningful tradeoff to expose as a setting.
     */
    private void maybeSyncLiveHoldings() {
        long now = System.currentTimeMillis();
        if (now < nextLiveHoldingsSyncAtMillis) return;
        nextLiveHoldingsSyncAtMillis = now + LIVE_HOLDINGS_SYNC_INTERVAL_MILLIS;
        portfolio.pushLiveHoldingsToFirestore();
    }

    /**
     * The DECIDE phase (PROPOSAL.md §2.4/§3.6): builds a state vector for every watchlisted item
     * via {@link DecisionEngine}, writes it to {@code decision/request}, and waits (bounded by
     * {@code decisionResponseTimeoutSeconds}, defaulting to "no suggestions this tick" on
     * timeout - see {@link DecisionEngine#decide}) for the model's proposed actions. Runs
     * independently of - and never blocks or is blocked by - the mechanical
     * SUBMITTING_ORDERS/MONITORING_OFFERS states above, which keep driving {@link OrderQueue} for
     * manual orders exactly as milestone 1 did.
     *
     * <p><b>Autonomous execution is gated by {@code config.autonomousModeEnabled()}, a dedicated
     * switch independent of {@code config.shadowMode()} (which stays inert - see its own
     * javadoc/config description).</b> The confidence filter is applied exactly once, up front,
     * to the raw actions from {@code decision/response} - the resulting {@code suggestions} list
     * is what both the panel display and (when enabled) autonomous submission operate on, so
     * there is exactly one confidence-checking code path, never two that could drift apart.
     *
     * <p>When autonomous mode is OFF (the default), behavior is unchanged from before this
     * method existed: {@code suggestions} is only ever handed to {@link DecisionSuggestions} for
     * display in the panel's "Model suggestions" section, and converting a suggestion into a real
     * order happens exactly one way - a human clicking Confirm (see
     * {@code PPOFlipperStarPanel#onConfirmSuggestionClicked}), which pushes onto
     * {@link OrderQueue} via {@link OrderQueue#add}.
     *
     * <p>When autonomous mode is ON, every surviving suggestion is submitted the same way a
     * manual Confirm click would: a brand-new {@link PPOFlipperOrder} built with the exact same
     * constructor-argument shape {@code onConfirmSuggestionClicked} uses, pushed onto
     * {@link OrderQueue} via {@link OrderQueue#add}. There is no second, different
     * order-construction path - autonomous and manual orders are indistinguishable to
     * {@link OrderQueue}/{@link Guardrails}/{@link PPOFlipperStarScript#submitNextOrder} from this
     * point on, so {@link Guardrails#check} applies identically regardless of origin. An
     * autonomously-submitted suggestion is removed from {@link DecisionSuggestions} immediately
     * (not left for the panel to render a Confirm button for something already queued) so the
     * panel accurately reflects "already submitted" vs. "awaiting your decision." Every
     * autonomous submission gets its own distinct, clearly-labeled log line for audit purposes,
     * separate from the manual-confirm log path.
     *
     * <p><b>{@code config.sellOffModeEnabled()} is a separate switch that auto-submits SELL
     * suggestions independent of {@code autonomousModeEnabled}</b> - meant for exercising the
     * SELL execution path end-to-end using the model's own recommendations (rather than a
     * hardcoded "sell everything held" rule), without a concurrent BUY going out. While it's on,
     * every BUY suggestion is filtered out of {@code suggestions} before it's shown or submitted,
     * and {@link Guardrails#check} independently hard-rejects any BUY order regardless of origin
     * as a second layer - see its javadoc.
     */
    private void runDecideTick(PPOFlipperStarConfig configSnapshot) {
        long tickStartMillis = System.currentTimeMillis();
        long timeoutMillis = Math.max(0, configSnapshot.decisionResponseTimeoutSeconds()) * 1000L;
        Optional<DecisionEngine.DecisionResult> result = decisionEngine.decide(timeoutMillis, configSnapshot.maxActiveOffers());

        if (decisionEngine.didLastDecideTimeOut()) {
            consecutiveDecideTimeouts.incrementAndGet();
        } else if (result.isPresent()) {
            // Only a genuine response resets the streak - the other Optional.empty() causes
            // (empty watchlist, sync disabled) are neither a timeout nor a real answer, so they
            // deliberately leave the counter exactly where it was rather than resetting it.
            consecutiveDecideTimeouts.set(0);
        }

        if (!result.isPresent()) {
            // No watchlisted items, sync unavailable, or a timeout - PROPOSAL.md §3.6: "a slow/
            // unreachable model must never block the trading loop." Nothing to show; leave
            // whatever suggestions are already in DecisionSuggestions untouched rather than
            // clearing them, so a transient timeout doesn't yank a suggestion out from under a
            // user mid-review.
            String outcome = decisionEngine.didLastDecideTimeOut() ? "TIMEOUT" : "EMPTY";
            diagnosticsLog.logTick(-1, System.currentTimeMillis() - tickStartMillis,
                decisionEngine.watchlistSize(), 0, 0, 0, outcome);
            return;
        }

        DecisionEngine.DecisionResult decision = result.get();

        // Debug-only visibility into the model's RAW, pre-filter output for every SELL-shaped
        // action this tick, regardless of confidence - added after a real question ("is the model
        // even emitting low-confidence SELLs, or genuinely proposing nothing for held items?")
        // that the post-filter suggestions count alone can't answer: decision.actions.size() (the
        // diagnostics log's "sent" field) is the raw count, but nothing previously logged WHICH
        // raw actions those were before modelConfidenceThreshold trimmed them. Deliberately
        // debug-level, not info - a large watchlist's raw response is too big to log at info every
        // tick, but this is exactly what's needed when specifically investigating "is a SELL just
        // under the bar, or is the model not proposing one at all."
        if (log.isDebugEnabled()) {
            String rawSells = decision.actions.stream()
                .filter(a -> a.action != null && a.action.startsWith("SELL"))
                .sorted(Comparator.comparingDouble((PPOFlipperStarFirestoreClient.DecisionAction a) -> a.confidence).reversed())
                .map(a -> String.format("item %d: %s conf=%.3f qty=%d price=%d", a.itemId, a.action, a.confidence, a.quantity, a.price))
                .collect(Collectors.joining(", "));
            log.debug("PPOFlipperStar: tick {} raw SELL-shaped actions before confidence filter ({} total): {}",
                decision.tickId, decision.actions.size(), rawSells.isEmpty() ? "none" : rawSells);
        }

        // Applied exactly once, before anything else - both the panel's "Model suggestions"
        // display and (when autonomousModeEnabled) autonomous submission below operate on this
        // same already-filtered `suggestions` list, so there is exactly one confidence-checking
        // code path for both purposes.
        double confidenceThreshold = Math.max(0.0, configSnapshot.modelConfidenceThreshold());

        boolean sellOffMode = configSnapshot.sellOffModeEnabled();

        List<PPOFlipperDecision> suggestions = decision.actions.stream()
            .filter(a -> a.confidence >= confidenceThreshold)
            .map(a -> toDecision(decision.tickId, a, decision.checkpointVersion))
            .filter(PPOFlipperDecision::isActionable)
            .filter(this::passesBuyCooldown)
            // Sell-off mode (see its config description): drop BUY suggestions before they're
            // ever shown or auto-submitted - Guardrails also hard-rejects any BUY that somehow
            // still reached order submission, but filtering here keeps the panel's "Model
            // suggestions" display honest about what will actually happen instead of showing a
            // BUY the user could Confirm only to have it bounce.
            .filter(d -> !sellOffMode || d.getGeAction() != GrandExchangeAction.BUY)
            .collect(Collectors.toList());

        if (configSnapshot.stalePositionAutoSellEnabled()) {
            addStalePositionSells(decision.tickId, decision.checkpointVersion, suggestions);
        }

        // Always populate DecisionSuggestions first, regardless of autonomous mode, so the panel
        // always shows what the model most recently proposed - an audit trail of the tick's
        // output whether or not it went on to auto-execute below.
        decisionSuggestions.replaceAll(decision.tickId, suggestions);
        if (!suggestions.isEmpty()) {
            log.info("PPOFlipperStar: DECIDE tick {} produced {} actionable suggestion(s) for review.",
                decision.tickId, suggestions.size());
        }
        diagnosticsLog.logTick(decision.tickId, System.currentTimeMillis() - tickStartMillis,
            decisionEngine.watchlistSize(), decision.actions.size(), suggestions.size(), 0, "OK");

        // Sell-off mode auto-submits SELL suggestions on its own, independent of
        // autonomousModeEnabled - the whole point is exercising the SELL path without a manual
        // Confirm click, and BUY suggestions have already been filtered out above (Guardrails
        // would reject them anyway). Checked as its own branch rather than folded into the
        // autonomousModeEnabled branch below so the two switches compose correctly if both
        // happen to be on at once - autonomouslySubmit is idempotent-safe to call at most once
        // per tick either way, so an early return avoids ever calling it twice on the same list.
        if (sellOffMode) {
            autonomouslySubmit(suggestions);
        } else if (configSnapshot.autonomousModeEnabled()) {
            autonomouslySubmit(suggestions);
        }
    }

    /**
     * Forces a synthetic SELL_100% suggestion (confidence 1.0, so it always clears the confidence
     * threshold) into {@code suggestions} for every open position held longer than
     * {@code stalePositionThresholdHours} - see {@code stalePositionAutoSellEnabled}'s config
     * description for why this exists at all: the trained policy is structurally biased toward
     * BUY over SELL (a SELL is only ever legal/rewarded in training when the item is already
     * held, so across a large watchlist there are always far more legal BUY opportunities than
     * SELL ones), which can otherwise let a portfolio grow indefinitely under autonomous mode with
     * nothing ever forcing an exit.
     *
     * <p>Uses {@link PortfolioManager#getOpenPositions}'s already-maintained weighted-average
     * acquisition timestamp ({@link CostBasisEntry#getHoldingDurationMillis}) - no new tracking
     * needed even though separate purchases of the same item happen at different times/prices,
     * since that weighted average already blends them into one well-defined position age.
     *
     * <p>Skipped entirely for an item that already has a real (model-proposed) SELL suggestion
     * this tick - never overrides or duplicates one, only fills the gap when the model proposed
     * nothing for that item at all. Priced via {@link DecisionEngine#getLatestPrice} (the same
     * non-blocking, cache-backed live price the model's own suggestions use); a position with no
     * live price available yet is skipped for this tick rather than guessed at - it'll be
     * reconsidered next tick once a price is cached.
     *
     * <p>A forced sell is a completely ordinary {@link PPOFlipperDecision} once constructed here -
     * it flows through the exact same confidence filter (trivially, at 1.0), SELL-first sort, and
     * {@link Guardrails#check} as any model-proposed suggestion, with no special-cased bypass.
     *
     * <p><b>Cross-checked against real live holdings ({@link PortfolioManager#getAllHoldings}),
     * NOT trusted from {@link CostBasisEntry#getQuantityHeld} alone - a real incident.</b> The
     * ledger can carry a stale/ghost entry for an item with a real tracked quantity but no
     * genuine recent acquisition (e.g. a zero/epoch {@code weightedAcquisitionTimestampMillis}
     * from data that predates this ledger, or a reconciled remote entry for stock that was sold
     * or transferred outside this plugin without the ledger ever being told). Confirmed live: this
     * produced forced SELL_100% suggestions for a whole family of longbow/shortbow items with a
     * reported holding duration of ~56 YEARS (a dead giveaway of an epoch-zero timestamp, not a
     * real position age), for quantities far exceeding what was actually held (Guardrails then
     * correctly rejected each one as "sell quantity N exceeds what's held (0)") - harmless in that
     * each was caught before submission, but pure wasted DECIDE-tick suggestion slots every tick,
     * and a real risk if any partial overlap with genuine holdings had let a wrong-quantity SELL
     * through. Every forced sell here is now clamped to {@code min(ledger quantity, live quantity)}
     * and skipped entirely if live quantity is 0 - the same real inventory+bank read Guardrails
     * itself checks, so this can never propose more than what could actually be sold.
     */
    private void addStalePositionSells(long tickId, String checkpointVersion, List<PPOFlipperDecision> suggestions) {
        long thresholdMillis = Math.max(0, config.stalePositionThresholdHours()) * 3_600_000L;
        if (thresholdMillis <= 0) return;

        Set<Integer> alreadySuggestedSell = suggestions.stream()
            .filter(d -> d.getGeAction() == GrandExchangeAction.SELL)
            .map(PPOFlipperDecision::getItemId)
            .collect(Collectors.toSet());

        Map<Integer, Integer> liveHoldings = portfolio.getAllHoldings();
        long now = System.currentTimeMillis();
        for (CostBasisEntry entry : portfolio.getOpenPositions()) {
            if (entry.getQuantityHeld() <= 0) continue;
            if (entry.getHoldingDurationMillis(now) < thresholdMillis) continue;
            if (alreadySuggestedSell.contains(entry.getItemId())) continue;

            int liveQuantity = liveHoldings.getOrDefault(entry.getItemId(), 0);
            if (liveQuantity <= 0) continue;
            int sellQuantity = Math.min(entry.getQuantityHeld(), liveQuantity);

            WikiPriceClient.Price price = decisionEngine.getLatestPrice(entry.getItemId());
            if (price == null || price.instaSellPrice <= 0) continue;

            String itemName = decisionEngine.getItemName(entry.getItemId());
            if (itemName == null) continue;

            PPOFlipperDecision forced = new PPOFlipperDecision(tickId, entry.getItemId(), itemName, "SELL_100%",
                GrandExchangeAction.SELL, sellQuantity, price.instaSellPrice, 1.0,
                checkpointVersion, now);
            suggestions.add(forced);
            log.info("PPOFlipperStar: forcing stale-position sell - {} held {} min (threshold {}h, ledger qty {}, live qty {})",
                forced, entry.getHoldingDurationMillis(now) / 60_000L, config.stalePositionThresholdHours(),
                entry.getQuantityHeld(), liveQuantity);
        }
    }

    /**
     * Dampens the trained policy's real bias toward repeatedly proposing cheap, high-buy-limit
     * staples (see {@code buySuggestionCooldownSeconds}'s config description for the root cause -
     * {@code env.py}'s buy-size formula scales with an item's GE buy limit, so items like Flax,
     * Steel knives, and arrowheads win disproportionately often). A BUY suggestion for an item
     * still within its cooldown is dropped before it ever reaches {@link DecisionSuggestions} or
     * autonomous submission, giving other watchlisted items a chance to surface instead. A
     * suggestion that passes marks that item's cooldown as starting now - so repeated ticks that
     * keep proposing the same item don't each reset a "last shown" clock that never actually
     * lets the cooldown expire; the intent is "this item gets a turn periodically," not "this
     * item is silenced only while the model is silent about it too."
     *
     * <p>SELL suggestions are never subject to this - {@code order.getGeAction()} for a SELL is
     * about stock already held, and suppressing it on a cooldown would mean sometimes NOT telling
     * the user (or the autonomous path) that the model wants to sell something they own, which is
     * a materially worse failure mode than seeing the same BUY idea again.
     */
    private boolean passesBuyCooldown(PPOFlipperDecision decision) {
        if (decision.getGeAction() != GrandExchangeAction.BUY) {
            return true;
        }
        int cooldownSeconds = config.buySuggestionCooldownSeconds();
        if (cooldownSeconds <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long lastSuggestedAt = lastBuySuggestionAtMillis.get(decision.getItemId());
        if (lastSuggestedAt != null && now - lastSuggestedAt < cooldownSeconds * 1000L) {
            return false;
        }

        lastBuySuggestionAtMillis.put(decision.getItemId(), now);
        return true;
    }

    // How large OrderQueue's QUEUED+SUBMITTED backlog is allowed to get before autonomous mode
    // stops adding more, as a multiple of maxActiveOffers - see autonomouslySubmit's javadoc for
    // why this exists. 3x gives some real headroom over the 8 physical GE slots (room for orders
    // waiting on funds/items, or waiting for a slot to free up) without letting the backlog grow
    // unbounded the way it did before this cap existed.
    private static final int AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER = 3;

    /**
     * Submits every suggestion in {@code suggestions} directly onto {@link OrderQueue}, mirroring
     * {@code PPOFlipperStarPanel#onConfirmSuggestionClicked}'s exact
     * {@code new PPOFlipperOrder(...)} construction byte-for-byte - deliberately not a second,
     * independently-maintained order-construction path that could diverge from the manual one.
     * Only called when {@code config.autonomousModeEnabled()} is true (checked by the caller,
     * {@link #runDecideTick}). Every order built here still passes through
     * {@link Guardrails#check} exactly like a manual order once {@link #submitNextOrder} reaches
     * it - this method only ever calls {@link OrderQueue#add}, the same single entry point manual
     * orders use; there is no bypass of that check anywhere in this path.
     *
     * <p><b>Two gates added after a real live-testing finding, both checked before the
     * pre-existing {@code queue.add} call, not instead of anything already there:</b> the DECIDE
     * phase re-evaluates the ENTIRE watchlist every {@code decisionTickIntervalSeconds} (as fast
     * as every 1 second) with no memory of what it already proposed - live testing (300+ item
     * watchlist, ~300 items above the confidence threshold most ticks) showed this queuing over
     * 1,500 orders in a matter of minutes against a GE that physically has 8 slots, with only
     * ~39 ever actually reaching a real submission and 11 ever filling. The existing
     * {@code Guardrails.checkDuplicateBuy} only rejects an exact-duplicate BUY for an item that
     * already has one queued/submitted ahead of it (and only for BUYs) - it runs too late (after
     * the order is already sitting in the queue taking up space) and doesn't cover SELL or the
     * "queue is already far bigger than the GE could ever drain" case at all.
     * <ul>
     *   <li><b>Per-item/action dedup</b>: skip a suggestion if {@link OrderQueue} already has a
     *   {@code QUEUED} or {@code SUBMITTED} order for the same item id AND the same
     *   {@link GrandExchangeAction} (BUY vs SELL kept separate - an existing BUY doesn't block a
     *   new SELL of the same item, they're not the same intent). This is what actually stops the
     *   exact-same-price repeat-spam behavior found live (Maple logs, Iron platebody proposed
     *   and queued again every tick while an equivalent order was already pending).</li>
     *   <li><b>Queue-depth cap (BUY only)</b>: skip all remaining BUY suggestions this tick once
     *   {@code QUEUED + SUBMITTED} count already reaches
     *   {@code maxActiveOffers * AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER} - a hard backstop against
     *   unbounded growth regardless of how diverse the proposed items are, independent of the
     *   per-item dedup above (which alone wouldn't have stopped 300 genuinely distinct items from
     *   still piling up 300 orders deep in one tick). Deliberately never applied to SELL (see the
     *   SELL-before-BUY ordering note below) - only a BUY actually grows the backlog this cap
     *   bounds, so gating a SELL on it is self-defeating.</li>
     * </ul>
     * Neither gate touches {@link Guardrails} or changes what executes once an order is actually
     * submitted - both are pre-filters on whether an order is worth queuing at all, applied
     * identically regardless of confidence/item, and both are logged at debug (not warn - this is
     * expected, frequent, normal backpressure once the queue has real depth, not an error
     * condition) so they don't spam the log the way 1,500 "AUTONOMOUS submit" lines did.
     *
     * <p><b>SELL-before-BUY ordering, added after a real incident:</b> {@link WatchlistManager#getAll}
     * returns a {@code LinkedHashSet} (insertion order), and {@code suggestions} originally kept
     * that same order every tick - once the queue-depth cap was hit mid-list, whatever came first
     * always won the remaining backlog headroom regardless of action type. Found live: with a
     * large, BUY-heavy watchlist, BUY suggestions consistently exhausted the cap before the loop
     * ever reached SELL suggestions, so the same real SELL (stock already held, real GP already
     * spent on it) got silently held off tick after tick while new speculative BUYs kept winning.
     * A SELL represents capital already committed that could be freed up; a missed BUY is just a
     * missed new opportunity - leaving real holdings stuck unsold is the worse outcome, so
     * {@code suggestions} is now sorted SELL-first, resolving the BUY-vs-SELL priority question.</p>
     *
     * <p><b>Confidence-descending within each action-type group, added after a second real
     * incident:</b> the SELL-first sort above was originally a stable sort that otherwise preserved
     * {@code suggestions}' original per-tick order - which is effectively watchlist-insertion/item-id
     * order ({@link WatchlistManager#getAll} returns a {@code LinkedHashSet}, itself seeded from
     * {@code modelTrainedItems} sorted by id). Once DECIDE ticks started reaching the full watchlist
     * (500-700+ suggestions in one tick is normal on a large watchlist) but the queue-depth cap below
     * only allows ~24 through, "first" meant lowest item id, not highest quality - live testing
     * confirmed every autonomous submission clustering under item id ~250 (arrowtips, bolts,
     * longbows/shortbows, herb potions), which looked exactly like a strong model preference for
     * that item family but was pure position-in-list luck, unrelated to how good any given
     * suggestion actually was. Sorting each action-type group by confidence descending means the
     * cap below, once hit, always drops the LEAST confident remaining suggestions first - confirmed
     * live after this fix: autonomous submissions immediately spanned nearly the entire item-id
     * range with confidence values correctly ranked highest-first.</p>
     *
     * <p>Removes each submitted suggestion from {@link DecisionSuggestions} immediately (the same
     * "confirmed, no longer pending" transition {@code onConfirmSuggestionClicked} performs) so
     * the panel never shows a Confirm button for something that has already been queued.
     */
    private void autonomouslySubmit(List<PPOFlipperDecision> suggestions) {
        int maxQueueDepth = Math.max(1, config.maxActiveOffers()) * AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER;

        List<PPOFlipperDecision> ordered = new ArrayList<>(suggestions);
        ordered.sort(Comparator
            .comparingInt((PPOFlipperDecision d) -> d.getGeAction() == GrandExchangeAction.SELL ? 0 : 1)
            .thenComparing(Comparator.comparingDouble(PPOFlipperDecision::getConfidence).reversed()));

        for (PPOFlipperDecision decision : ordered) {
            // The backlog-depth cap only ever gates BUY, never SELL - see incident-notes/
            // fix-guide-autonomous-item-clustering.md's "related, separate bug" section. Only a
            // BUY actually grows OrderQueue's backlog, so only BUY needs to be bounded by it; a
            // SELL was already being placed first by the confidence sort above, but the cap check
            // ran unconditionally on every iteration against the LIVE queue - once prior ticks'
            // still-pending BUYs had already pushed the real backlog above the cap, the very
            // first item checked this tick (even a real, high-confidence SELL the sort just moved
            // to the front) failed the cap before the loop ever got a chance to submit it. A SELL
            // represents capital/inventory already committed that could be freed up - gating it on
            // a cap meant to bound queue GROWTH is self-defeating.
            if (decision.getGeAction() != GrandExchangeAction.SELL) {
                long currentBacklog = queue.countByStatus(PPOFlipperOrder.Status.QUEUED)
                    + queue.countByStatus(PPOFlipperOrder.Status.SUBMITTED);
                if (currentBacklog >= maxQueueDepth) {
                    log.info("PPOFlipperStar: autonomous queue backlog at {} (cap {}), holding off on {} this tick.",
                        currentBacklog, maxQueueDepth, decision);
                    break;
                }
            }

            // A BUY always fills to inventory (GE collection, not the bank) - if inventory is
            // already full and this item isn't already stacking there, this BUY can never
            // complete, so there's no point building it just to have Guardrails.check() reject it
            // moments later. Effectively "hold" on this suggestion: nothing is submitted, and the
            // model gets a fresh, unbiased chance to propose it again next tick once space frees
            // up (e.g. a SELL going through, or the item being manually banked). See
            // Guardrails.checkInventorySpace for the same check, kept there too as the actual
            // enforcement backstop for any BUY (manual or autonomous) - this is purely an early,
            // quieter skip so autonomous BUYs doomed to fail don't consume a queue-depth-cap slot
            // or log a rejection line every tick while inventory stays full.
            if (decision.getGeAction() == GrandExchangeAction.BUY) {
                boolean alreadyStacking = decision.getItemId() > 0
                    ? Rs2Inventory.hasItem(decision.getItemId())
                    : Rs2Inventory.hasItem(decision.getItemName());
                if (!alreadyStacking && Rs2Inventory.isFull()) {
                    log.debug("PPOFlipperStar: holding off on autonomous {} - inventory is full and {} isn't already held.",
                        decision, decision.getItemName());
                    continue;
                }
            }

            boolean alreadyPending = queue.getAll().stream()
                .anyMatch(o -> o.getItemId() == decision.getItemId()
                    && o.getAction() == decision.getGeAction()
                    && (o.getStatus() == PPOFlipperOrder.Status.QUEUED || o.getStatus() == PPOFlipperOrder.Status.SUBMITTED));
            if (alreadyPending) {
                log.info("PPOFlipperStar: skipping autonomous {} - an equivalent order for {} is already queued/submitted.",
                    decision, decision.getItemName());
                decisionSuggestions.remove(decision.getId());
                continue;
            }

            if (!passesRejectionCooldown(decision)) {
                log.info("PPOFlipperStar: skipping autonomous {} - still within its post-rejection cooldown.", decision);
                decisionSuggestions.remove(decision.getId());
                continue;
            }

            PPOFlipperOrder candidate = new PPOFlipperOrder(decision.getGeAction(), decision.getItemId(),
                decision.getItemName(), decision.getQuantity(), decision.getPrice());

            // Speculatively checked here, BEFORE queuing, rather than letting submitNextOrder's
            // own Guardrails.check() reject it later - a real incident: without this, the same
            // guardrail-rejected item/action was being re-proposed and re-queued-then-rejected
            // every DECIDE tick (sometimes several times a minute), since nothing remembered "this
            // was just tried and failed." Guardrails.check() is a pure read (no side effects - see
            // its own javadoc), so calling it here and again inside submitNextOrder for the same
            // order if it passes is redundant but harmless, not double-counted spend/state.
            String rejection = guardrails.check(candidate);
            if (rejection != null) {
                recordAutonomousRejection(decision);
                log.info("PPOFlipperStar: withheld autonomous {} - would be rejected: {}", decision, rejection);
                decisionSuggestions.remove(decision.getId());
                continue;
            }

            queue.add(candidate);
            decisionSuggestions.remove(decision.getId());
            log.info("PPOFlipperStar: AUTONOMOUS submit - {} (confidence {})", decision, decision.getConfidence());
        }
    }

    private static String rejectionCooldownKey(int itemId, GrandExchangeAction action) {
        return itemId + ":" + action;
    }

    /**
     * True if {@code decision}'s item+action wasn't rejected by Guardrails within the last
     * {@code autonomousRejectionCooldownSeconds} - see {@link #lastAutonomousRejectionAtMillis}'s
     * javadoc. A cooldown, not a permanent block: once it expires, the exact same item+action is
     * fully eligible again on the very next DECIDE tick, so a rejection whose underlying cause
     * clears (the item is acquired, its price moves back in range, a queue slot frees up) isn't
     * suppressed indefinitely - only the tight, wasteful re-reject-every-tick loop is.
     *
     * <p><b>Originally a hardcoded 60s, made configurable after a real incident:</b> with
     * {@code decisionTickIntervalSeconds} around 5-10s and a large watchlist, a 60s cooldown meant
     * a rejected item went completely silent for 6-12 consecutive ticks - watching the actual live
     * behavior, this looked like "almost nothing ever gets submitted despite the model producing
     * plenty of suggestions every tick," which is a real usability problem, not a safety one (the
     * cooldown's only job is stopping the reject-every-tick spam loop, not gatekeeping trades).
     */
    private boolean passesRejectionCooldown(PPOFlipperDecision decision) {
        if (decision.getGeAction() == null) return true;
        long cooldownMillis = Math.max(0, config.autonomousRejectionCooldownSeconds()) * 1000L;
        if (cooldownMillis <= 0) return true;
        Long lastRejectedAt = lastAutonomousRejectionAtMillis.get(rejectionCooldownKey(decision.getItemId(), decision.getGeAction()));
        if (lastRejectedAt == null) return true;
        return System.currentTimeMillis() - lastRejectedAt >= cooldownMillis;
    }

    private void recordAutonomousRejection(PPOFlipperDecision decision) {
        lastAutonomousRejectionAtMillis.put(rejectionCooldownKey(decision.getItemId(), decision.getGeAction()),
            System.currentTimeMillis());
    }

    /**
     * Converts one raw {@code decision/response} action entry into a {@link PPOFlipperDecision},
     * resolving the item's display name and mapping the action-name string onto a
     * {@link GrandExchangeAction} for BUY/SELL tiers (null for HOLD).
     *
     * <p>Resolves the name via {@link DecisionEngine#getItemName}, NOT
     * {@code Rs2ItemManager.getItemComposition} - a real incident, confirmed live via jstack: the
     * old code called {@code getItemComposition} (a blocking client-thread round trip) TWICE per
     * suggestion, inside the {@code .map()} this method backs (see {@code runDecideTick}) over
     * every action in a tick's response - hundreds of suggestions per tick on a large watchlist
     * meant hundreds of sequential blocking calls stalling the DECIDE thread for minutes.
     * {@link DecisionEngine} already bulk-fetches every tradeable item's name once per tick
     * (see its {@code refreshItemMappings} javadoc) for exactly this kind of lookup - reusing that
     * cache here is a plain, instant map read instead of a second per-item network/client-thread
     * round trip.
     */
    private PPOFlipperDecision toDecision(long tickId, PPOFlipperStarFirestoreClient.DecisionAction action,
                                           String checkpointVersion) {
        String resolvedName = decisionEngine.getItemName(action.itemId);
        String itemName = resolvedName != null ? resolvedName : ("item " + action.itemId);

        GrandExchangeAction geAction = null;
        if (action.action != null) {
            if (action.action.startsWith("BUY")) {
                geAction = GrandExchangeAction.BUY;
            } else if (action.action.startsWith("SELL")) {
                geAction = GrandExchangeAction.SELL;
            }
        }

        return new PPOFlipperDecision(tickId, action.itemId, itemName, action.action, geAction,
            action.quantity, action.price, action.confidence, checkpointVersion, System.currentTimeMillis());
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

        // Set as soon as this flow starts needing the bank open, and only cleared once it's
        // actually done with it (the close below) - deliberately NOT a try/finally scoped to this
        // single call, since this method can return with the bank still legitimately open,
        // resuming next tick (still this same withdrawal flow, still intentional) rather than
        // finishing in one call. See guardAgainstUnexpectedBank()'s javadoc for what this flag is
        // protecting against while it's true.
        intentionalBankUseActive = true;

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) {
                intentionalBankUseActive = false;
                return;
            }
            sleepUntil(Rs2Bank::isOpen);
        }

        if (orderAwaitingFunds.getAction() == GrandExchangeAction.BUY) {
            // Coins can never be noted (a game-engine restriction, not a Microbot limitation) -
            // no note-mode handling needed here.
            long currentCoins = Rs2Inventory.itemQuantity(ItemID.COINS);
            long needed = orderAwaitingFunds.totalValue() - currentCoins;
            if (needed > 0) {
                // If a gold reserve target is configured, top up to that level instead of just
                // this order's exact shortfall - so later orders this session can draw down the
                // reserve already sitting in inventory without triggering another bank trip each
                // time. Withdraw whichever is larger: the order's real need (the reserve target
                // could be set below what a single big order actually costs) or the top-up to
                // the target. 0 (the default) reproduces the old exact-need-only behavior exactly.
                long reserveTarget = Math.max(0, config.goldReserveTarget());
                long topUpAmount = Math.max(needed, reserveTarget - currentCoins);
                Rs2Bank.withdrawX(ItemID.COINS, (int) topUpAmount);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        } else {
            int have = Rs2Inventory.itemQuantity(orderAwaitingFunds.getItemName());
            int needed = orderAwaitingFunds.getQuantity() - have;
            if (needed > 0) {
                withdrawPreferringNotes(orderAwaitingFunds.getItemId(), orderAwaitingFunds.getItemName(), needed);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        intentionalBankUseActive = false;
        orderAwaitingFunds = null;
        state = State.SUBMITTING_ORDERS;
    }

    /**
     * Withdraws a SELL order's item, preferring noted stock when the item actually has a noted
     * variant - a noted withdrawal takes one inventory slot regardless of quantity instead of
     * however many unnoted stacks the bank happens to split it into (most tradeable items already
     * only stack unnoted if they're stackable at all - notes exist specifically for the ones that
     * don't). The GE accepts noted items for a SELL exactly like unnoted ones, no unnoting step
     * needed - and {@code Rs2Inventory.itemQuantity(String)}'s pre-withdrawal check above already
     * sums noted+unnoted quantities together by display name (verified against the client jar's
     * bytecode - it filters by {@code getName()}, not item id, so it can't tell them apart to
     * begin with), so switching what actually comes out of the bank doesn't change what that
     * check already believed was available.
     *
     * <p>{@link Rs2ItemModel#getNotedId(int)} returns {@code -1} for an item with no noted
     * variant (most equipment, and a handful of never-stackable items) - falls back to a normal
     * unnoted withdrawal in that case, since forcing note-mode on first would just waste a widget
     * click for nothing. Note-mode is restored back to item-mode afterward regardless of which
     * path was taken, so it never leaks into an unrelated later bank interaction (a manual
     * withdrawal via the panel, or a different script) that isn't expecting it.
     */
    private void withdrawPreferringNotes(int itemId, String itemName, int quantity) {
        boolean notable = Rs2ItemModel.getNotedId(itemId) != -1;
        if (!notable) {
            Rs2Bank.withdrawX(itemName, quantity);
            return;
        }

        Rs2Bank.setWithdrawAsNote();
        sleep(300, 600);
        if (!Rs2Bank.hasWithdrawAsNote()) {
            log.warn("PPOFlipperStar: could not switch bank to note mode for {}, withdrawing unnoted instead.", itemName);
            Rs2Bank.withdrawX(itemName, quantity);
            return;
        }

        Rs2Bank.withdrawX(itemName, quantity);
        Rs2Bank.setWithdrawAsItem();
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
            order.setSubmittedAtMillis(System.currentTimeMillis());
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
        int itemId = order.getItemId() > 0 ? order.getItemId() : Rs2ItemManager.getItemIdByName(order.getItemName(), true);
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
            int floored = order.getPrice();
            if (price.instaSellPrice > 0) {
                floored = Math.max(floored, price.instaSellPrice);
            }
            if (floored > order.getPrice()) {
                log.info("PPOFlipperStar: raised sell price for {} from {} to live insta-sell price {}",
                    order.getItemName(), order.getPrice(), price.instaSellPrice);
                order.setStatusDetail(String.format(
                    "Price raised to live insta-sell %d gp (requested %d gp)", floored, order.getPrice()));
            }
            return applyMinSellMargin(order, floored);
        }
    }

    /**
     * A second, independent floor beyond the live insta-sell price above: the trained policy's
     * sell-price offset (env.py's SELL_PRICE_OFFSET_FRAC - up to 30% of the live spread conceded
     * for a SELL_100) is chosen purely from live market spread, with zero awareness of what was
     * actually paid for the position being sold - it can legitimately undersell a real profit
     * margin in exchange for a faster fill. This guarantees a minimum realized margin over the
     * position's own tracked average cost, independent of what the model or the insta-sell floor
     * above requested.
     *
     * <p>Only applies when {@link PortfolioManager#getAverageCost} has real tracked cost data for
     * this item ({@code > 0}) - for untracked/pre-existing stock (cost basis unknown, see
     * {@link PortfolioManager}'s javadoc on selling more than tracked as held), there's nothing to
     * compute a margin against, so this silently no-ops and only the insta-sell floor applies, same
     * as before this guard existed.
     *
     * <p>Raises the price to meet the floor rather than rejecting the order outright, matching the
     * insta-sell floor's own behavior above - the trade still goes out (may fill slower now that
     * it's less aggressively priced, but that's now bounded by {@code staleOfferTimeoutMinutes}
     * rather than waiting forever) instead of silently not happening at all this tick.
     */
    private int applyMinSellMargin(PPOFlipperOrder order, int candidatePrice) {
        double marginPercent = config.minSellProfitMarginPercent();
        if (marginPercent <= 0) return candidatePrice;

        int itemId = order.getItemId() > 0 ? order.getItemId() : Rs2ItemManager.getItemIdByName(order.getItemName(), true);
        int averageCost = itemId > 0 ? portfolio.getAverageCost(itemId) : 0;
        if (averageCost <= 0) return candidatePrice;

        int minPrice = (int) Math.ceil(averageCost * (1.0 + marginPercent / 100.0));
        if (candidatePrice >= minPrice) return candidatePrice;

        log.info("PPOFlipperStar: raised sell price for {} from {} to minimum margin price {} ({}% over avg cost {})",
            order.getItemName(), candidatePrice, minPrice, marginPercent, averageCost);
        order.setStatusDetail(String.format(
            "Price raised to %d gp to guarantee %.1f%% margin over avg cost %d gp", minPrice, marginPercent, averageCost));
        return minPrice;
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
            } else if (isDud(order) && isStale(order) && queue.nextQueued().isPresent()) {
                abortStaleOffer(slot, order);
            }
            queue.notifyChanged();
        }

        evictForBlockedSell();

        if (activeOrders.isEmpty() && !queue.nextQueued().isPresent()) {
            state = State.DONE;
        } else if (activeOrders.size() < Math.max(1, config.maxActiveOffers()) && queue.nextQueued().isPresent()) {
            state = State.SUBMITTING_ORDERS;
        } else if (!activeOrders.isEmpty()) {
            state = State.MONITORING_OFFERS;
        }
    }

    /**
     * True if {@code order} has been SUBMITTED for longer than {@code staleOfferTimeoutMinutes}
     * (0 disables this - never stale). Only ever consulted for a "dud" offer per {@link #isDud} -
     * a real, meaningful partial fill is never considered stale regardless of age, since aborting
     * it would strand the already-filled portion's exit strategy along with the cancelled
     * remainder.
     *
     * <p>Being stale by itself is NOT sufficient to abort an offer - see that same call site's
     * additional {@code queue.nextQueued().isPresent()} check: a stale offer only actually gets
     * pulled once something else is genuinely waiting on the slot it occupies. An idle GE slot
     * holding a slow-moving offer costs nothing while nothing else wants that slot, so there's no
     * reason to force a re-decide just because a timer elapsed - only do it when the freed slot
     * would immediately go to real, queued work instead of sitting empty.
     */
    private boolean isStale(PPOFlipperOrder order) {
        int timeoutMinutes = config.staleOfferTimeoutMinutes();
        if (timeoutMinutes <= 0 || order.getSubmittedAtMillis() <= 0) {
            return false;
        }
        long ageMillis = System.currentTimeMillis() - order.getSubmittedAtMillis();
        return ageMillis >= timeoutMinutes * 60_000L;
    }

    /**
     * True if {@code order}'s fill percentage is low enough, RELATIVE TO HOW LONG IT'S BEEN LIVE,
     * to be functionally a dud rather than a real partial position worth protecting - see
     * {@code dudFillPercentThreshold}'s config description for the real incident this addresses (a
     * BUY that filled ~2% then stalled completely, previously immune to staleness cleanup forever
     * since the old check required EXACTLY {@code filled == 0}).
     *
     * <p><b>Dynamic, not a fixed bar:</b> the required fill to escape dud status ramps linearly
     * from 0% right at submission up to {@code dudFillPercentThreshold} at
     * {@code staleOfferTimeoutMinutes} old (and stays at that threshold beyond it - this method
     * doesn't itself gate on age past that point, {@link #isStale} still does that separately).
     * A brand-new order isn't penalized for having 0% fill in its first few seconds - almost
     * anything above 0% clears the bar early on - but the tolerance for a low fill shrinks as the
     * order approaches the point {@link #isStale} would flag it anyway, so an order that's clearly
     * stalling relative to its own age gets caught without waiting the full fixed timeout at a
     * flat threshold the whole time. A fully-unfilled order (0%) always counts as a dud regardless
     * of age. {@code dudFillPercentThreshold} <= 0 restores the old strict "only filled == 0"
     * behavior (the ramp is skipped entirely).
     */
    private boolean isDud(PPOFlipperOrder order) {
        if (order.getQuantityFilled() == 0) {
            return true;
        }
        int thresholdPercent = config.dudFillPercentThreshold();
        if (thresholdPercent <= 0 || order.getQuantity() <= 0) {
            return false;
        }
        double filledPercent = order.getQuantityFilled() * 100.0 / order.getQuantity();

        int timeoutMinutes = config.staleOfferTimeoutMinutes();
        if (timeoutMinutes <= 0 || order.getSubmittedAtMillis() <= 0) {
            // Staleness is disabled entirely (or this order was never actually submitted) - no
            // age reference to ramp against, fall back to the flat threshold.
            return filledPercent < thresholdPercent;
        }
        long ageMillis = System.currentTimeMillis() - order.getSubmittedAtMillis();
        double ageFraction = Math.min(1.0, ageMillis / (double) (timeoutMinutes * 60_000L));
        double requiredPercent = thresholdPercent * ageFraction;
        return filledPercent < requiredPercent;
    }

    /**
     * Aborts a stale, dud (see {@link #isDud}) offer whose slot is genuinely wanted by something
     * else right now (see {@link #isStale}'s javadoc for the "only if actually needed" gate this
     * is called under), and collects whatever comes back (the already-filled portion, if any - see
     * {@link #isDud}, this can now fire on a small-but-nonzero fill, not just a literal 0) via
     * {@code Rs2GrandExchange.cancelSpecificOffers}, which aborts then internally collects in one
     * call - no separate {@code collectOffer} needed afterward, and the filled portion is not lost.
     * Deliberately does NOT requeue the order itself: per {@code staleOfferTimeoutMinutes}'s config
     * description, the point is to let the item go back through a fresh DECIDE tick and get
     * re-evaluated with the model's current judgment (spread/volatility/momentum/holding-duration),
     * not to blindly resubmit the same stale price - a hardcoded reprice-and-retry here would
     * defeat that purpose. The order is marked SKIPPED (an audit trail explaining why it vanished
     * from the queue) rather than left QUEUED, since re-queuing it verbatim would just recreate the
     * same stale price/quantity the model may no longer agree with.
     */
    private void abortStaleOffer(GrandExchangeSlots slot, PPOFlipperOrder order) {
        log.info("PPOFlipperStar: aborting stale dud offer in slot {} - {} ({}/{} filled, submitted {} min ago)",
            slot, order, order.getQuantityFilled(), order.getQuantity(),
            (System.currentTimeMillis() - order.getSubmittedAtMillis()) / 60_000L);
        cancelAndFreeSlot(slot, order, "Aborted - stale dud (" + order.getQuantityFilled() + "/" + order.getQuantity()
            + " filled) after " + config.staleOfferTimeoutMinutes() + " min");
    }

    /** Shared cancel/collect/mark-skipped mechanics for {@link #abortStaleOffer} and {@link #evictForBlockedSell}. */
    private void cancelAndFreeSlot(GrandExchangeSlots slot, PPOFlipperOrder order, String skippedReason) {
        Rs2GrandExchange.cancelSpecificOffers(List.of(slot), config.collectToBank());
        markSkipped(order, skippedReason);
        activeOrders.remove(slot);
    }

    /**
     * A QUEUED SELL represents capital/inventory already committed - if every GE slot is tied up
     * with dud BUYs (see {@link #isDud} - unfilled or negligibly filled, still-speculative
     * opportunities either way) and the SELL has been waiting past {@code sellSlotEvictionWaitSeconds},
     * this cancels the single oldest eligible BUY to make room for it, rather than making it wait
     * on {@link #isStale}'s much longer {@code staleOfferTimeoutMinutes} timer. Deliberately
     * narrow: only fires when {@link OrderQueue#nextQueued()} itself resolves to a SELL (i.e.
     * nothing else already jumps the line ahead of it - see that method's javadoc) AND every slot
     * is full AND that SELL has actually been queued long enough. "Eligible" BUY means a dud per
     * {@link #isDud} (a real, meaningful partial fill is never touched, same protection
     * {@link #isStale} already applies) and at least {@code sellSlotEvictionMinBuyAgeSeconds} old,
     * so a BUY that hasn't had a fair chance to fill yet is never sacrificed just because it
     * happens to be the only one active. If no BUY qualifies yet, the SELL simply keeps waiting -
     * this never forces an eviction, only offers one once a genuinely reasonable candidate exists.
     */
    private void evictForBlockedSell() {
        int waitSeconds = config.sellSlotEvictionWaitSeconds();
        if (waitSeconds <= 0) {
            log.debug("PPOFlipperStar: evictForBlockedSell - disabled (sellSlotEvictionWaitSeconds=0).");
            return;
        }
        if (activeOrders.size() < Math.max(1, config.maxActiveOffers())) {
            // A slot is already free - nextQueued() (SELL-first) will claim it on the normal
            // SUBMITTING_ORDERS path next, no eviction needed.
            log.debug("PPOFlipperStar: evictForBlockedSell - {} of {} slots active, a slot is already free.",
                activeOrders.size(), Math.max(1, config.maxActiveOffers()));
            return;
        }

        Optional<PPOFlipperOrder> nextQueued = queue.nextQueued();
        if (!nextQueued.isPresent()) {
            log.debug("PPOFlipperStar: evictForBlockedSell - all {} slots full, nothing QUEUED.", activeOrders.size());
            return;
        }
        if (nextQueued.get().getAction() != GrandExchangeAction.SELL) {
            log.debug("PPOFlipperStar: evictForBlockedSell - all {} slots full, but next QUEUED order is a {} not a SELL - {}.",
                activeOrders.size(), nextQueued.get().getAction(), nextQueued.get());
            return;
        }
        PPOFlipperOrder blockedSell = nextQueued.get();
        long queuedForMillis = System.currentTimeMillis() - blockedSell.getQueuedAtMillis();
        if (queuedForMillis < waitSeconds * 1000L) {
            log.info("PPOFlipperStar: evictForBlockedSell - SELL {} is next in queue but all {} slots are full; " +
                    "waiting {}s more (queued {}s ago, grace period {}s) before considering an eviction.",
                blockedSell, activeOrders.size(), waitSeconds - (queuedForMillis / 1000L), queuedForMillis / 1000L, waitSeconds);
            return;
        }

        long minBuyAgeMillis = Math.max(0, config.sellSlotEvictionMinBuyAgeSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        Map.Entry<GrandExchangeSlots, PPOFlipperOrder> oldestEligibleBuy = activeOrders.entrySet().stream()
            .filter(e -> e.getValue().getAction() == GrandExchangeAction.BUY)
            .filter(e -> isDud(e.getValue()))
            .filter(e -> e.getValue().getSubmittedAtMillis() > 0
                && now - e.getValue().getSubmittedAtMillis() >= minBuyAgeMillis)
            .min(Comparator.comparingLong(e -> e.getValue().getSubmittedAtMillis()))
            .orElse(null);
        if (oldestEligibleBuy == null) {
            // No BUY is both a dud (see isDud - unfilled or negligibly filled) and old enough yet
            // - let the SELL keep waiting rather than sacrificing one that hasn't had a fair
            // chance, or a real partial fill worth protecting.
            log.info("PPOFlipperStar: evictForBlockedSell - SELL {} has waited {}s past the grace period, but no " +
                    "active BUY is both a dud (unfilled or negligibly filled) and at least {}s old yet - holding " +
                    "off eviction this tick. Active orders: {}",
                blockedSell, queuedForMillis / 1000L, config.sellSlotEvictionMinBuyAgeSeconds(),
                activeOrders.values());
            return;
        }

        log.info("PPOFlipperStar: evicting BUY in slot {} - {} - to free a slot for blocked SELL {} (queued {}s)",
            oldestEligibleBuy.getKey(), oldestEligibleBuy.getValue(), blockedSell, queuedForMillis / 1000L);
        cancelAndFreeSlot(oldestEligibleBuy.getKey(), oldestEligibleBuy.getValue(),
            "Evicted to free a GE slot for a blocked SELL (" + blockedSell.getItemName() + ")");
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
