package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Inject
    public PPOFlipperStarScript(OrderQueue queue, PortfolioManager portfolio, BuyLimitLedger buyLimitLedger,
                                 GoldManager goldManager, PPOFlipperStarFirestoreSync firestoreSync,
                                 DecisionEngine decisionEngine, DecisionSuggestions decisionSuggestions) {
        this.queue = queue;
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.goldManager = goldManager;
        this.firestoreSync = firestoreSync;
        this.decisionEngine = decisionEngine;
        this.decisionSuggestions = decisionSuggestions;
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
        this.nextDecisionTickAtMillis = 0;
        this.nextBankRefreshAtMillis = 0;
        this.nextLiveHoldingsSyncAtMillis = 0;
        this.lastBuySuggestionAtMillis.clear();
        this.lastAutonomousRejectionAtMillis.clear();
        this.decideInFlight.set(false);
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
                    decideInFlight.set(false);
                }
            });
        } catch (Exception e) {
            decideInFlight.set(false);
        }
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
        long timeoutMillis = Math.max(0, configSnapshot.decisionResponseTimeoutSeconds()) * 1000L;
        Optional<DecisionEngine.DecisionResult> result = decisionEngine.decide(timeoutMillis, configSnapshot.maxActiveOffers());
        if (!result.isPresent()) {
            // No watchlisted items, sync unavailable, or a timeout - PROPOSAL.md §3.6: "a slow/
            // unreachable model must never block the trading loop." Nothing to show; leave
            // whatever suggestions are already in DecisionSuggestions untouched rather than
            // clearing them, so a transient timeout doesn't yank a suggestion out from under a
            // user mid-review.
            return;
        }

        DecisionEngine.DecisionResult decision = result.get();
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

        // Always populate DecisionSuggestions first, regardless of autonomous mode, so the panel
        // always shows what the model most recently proposed - an audit trail of the tick's
        // output whether or not it went on to auto-execute below.
        decisionSuggestions.replaceAll(decision.tickId, suggestions);
        if (!suggestions.isEmpty()) {
            log.info("PPOFlipperStar: DECIDE tick {} produced {} actionable suggestion(s) for review.",
                decision.tickId, suggestions.size());
        }

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
     *   <li><b>Queue-depth cap</b>: skip ALL remaining suggestions this tick once
     *   {@code QUEUED + SUBMITTED} count already reaches
     *   {@code maxActiveOffers * AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER} - a hard backstop against
     *   unbounded growth regardless of how diverse the proposed items are, independent of the
     *   per-item dedup above (which alone wouldn't have stopped 300 genuinely distinct items from
     *   still piling up 300 orders deep in one tick).</li>
     * </ul>
     * Neither gate touches {@link Guardrails} or changes what executes once an order is actually
     * submitted - both are pre-filters on whether an order is worth queuing at all, applied
     * identically regardless of confidence/item, and both are logged at debug (not warn - this is
     * expected, frequent, normal backpressure once the queue has real depth, not an error
     * condition) so they don't spam the log the way 1,500 "AUTONOMOUS submit" lines did.
     *
     * <p><b>Known limitation, not fixed here:</b> {@link WatchlistManager#getAll} returns a
     * {@code LinkedHashSet} (insertion order), and {@code suggestions} is built by iterating that
     * same order every tick - once the queue-depth cap is hit mid-list, the items processed so
     * far (earliest-added to the watchlist) always win the remaining backlog headroom, and
     * later-added items are more likely to be the ones held off. A fair-rotation scheme (e.g.
     * round-robin starting point per tick) would address this but is real additional complexity
     * not justified by this fix's actual goal - stopping unbounded backlog growth - so it's left
     * as a known, documented tradeoff rather than silently ignored.</p>
     *
     * <p>Removes each submitted suggestion from {@link DecisionSuggestions} immediately (the same
     * "confirmed, no longer pending" transition {@code onConfirmSuggestionClicked} performs) so
     * the panel never shows a Confirm button for something that has already been queued.
     */
    private void autonomouslySubmit(List<PPOFlipperDecision> suggestions) {
        int maxQueueDepth = Math.max(1, config.maxActiveOffers()) * AUTONOMOUS_QUEUE_DEPTH_MULTIPLIER;

        for (PPOFlipperDecision decision : suggestions) {
            long currentBacklog = queue.countByStatus(PPOFlipperOrder.Status.QUEUED)
                + queue.countByStatus(PPOFlipperOrder.Status.SUBMITTED);
            if (currentBacklog >= maxQueueDepth) {
                log.debug("PPOFlipperStar: autonomous queue backlog at {} (cap {}), holding off on {} this tick.",
                    currentBacklog, maxQueueDepth, decision);
                break;
            }

            boolean alreadyPending = queue.getAll().stream()
                .anyMatch(o -> o.getItemId() == decision.getItemId()
                    && o.getAction() == decision.getGeAction()
                    && (o.getStatus() == PPOFlipperOrder.Status.QUEUED || o.getStatus() == PPOFlipperOrder.Status.SUBMITTED));
            if (alreadyPending) {
                log.debug("PPOFlipperStar: skipping autonomous {} - an equivalent order for {} is already queued/submitted.",
                    decision, decision.getItemName());
                decisionSuggestions.remove(decision.getId());
                continue;
            }

            if (!passesRejectionCooldown(decision)) {
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
                log.debug("PPOFlipperStar: withheld autonomous {} - would be rejected: {}", decision, rejection);
                decisionSuggestions.remove(decision.getId());
                continue;
            }

            queue.add(candidate);
            decisionSuggestions.remove(decision.getId());
            log.info("PPOFlipperStar: AUTONOMOUS submit - {} (confidence {})", decision, decision.getConfidence());
        }
    }

    // How long an item+action combo stays throttled after autonomouslySubmit found it would be
    // rejected by Guardrails - see lastAutonomousRejectionAtMillis' javadoc for the incident this
    // fixes. Not user-configurable: this is purely a churn-prevention measure with no trading-
    // strategy tradeoff to expose, unlike buySuggestionCooldownSeconds (which changes what
    // suggestions surface at all).
    private static final long AUTONOMOUS_REJECTION_COOLDOWN_MILLIS = 60_000L;

    private static String rejectionCooldownKey(int itemId, GrandExchangeAction action) {
        return itemId + ":" + action;
    }

    /**
     * True if {@code decision}'s item+action wasn't rejected by Guardrails within the last
     * {@link #AUTONOMOUS_REJECTION_COOLDOWN_MILLIS} - see {@link #lastAutonomousRejectionAtMillis}'s
     * javadoc. A cooldown, not a permanent block: once it expires, the exact same item+action is
     * fully eligible again on the very next DECIDE tick, so a rejection whose underlying cause
     * clears (the item is acquired, its price moves back in range, a queue slot frees up) isn't
     * suppressed indefinitely - only the tight, wasteful re-reject-every-tick loop is.
     */
    private boolean passesRejectionCooldown(PPOFlipperDecision decision) {
        if (decision.getGeAction() == null) return true;
        Long lastRejectedAt = lastAutonomousRejectionAtMillis.get(rejectionCooldownKey(decision.getItemId(), decision.getGeAction()));
        if (lastRejectedAt == null) return true;
        return System.currentTimeMillis() - lastRejectedAt >= AUTONOMOUS_REJECTION_COOLDOWN_MILLIS;
    }

    private void recordAutonomousRejection(PPOFlipperDecision decision) {
        lastAutonomousRejectionAtMillis.put(rejectionCooldownKey(decision.getItemId(), decision.getGeAction()),
            System.currentTimeMillis());
    }

    /** Converts one raw {@code decision/response} action entry into a {@link PPOFlipperDecision}, resolving the item's display name via {@link Rs2ItemManager} and mapping the action-name string onto a {@link GrandExchangeAction} for BUY/SELL tiers (null for HOLD). */
    private PPOFlipperDecision toDecision(long tickId, PPOFlipperStarFirestoreClient.DecisionAction action,
                                           String checkpointVersion) {
        String itemName = itemManager.getItemComposition(action.itemId) != null
            ? itemManager.getItemComposition(action.itemId).getName()
            : ("item " + action.itemId);

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

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) return;
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
            } else if (filled == 0 && isStale(order) && queue.nextQueued().isPresent()) {
                abortStaleOffer(slot, order);
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
     * True if {@code order} has been SUBMITTED for longer than {@code staleOfferTimeoutMinutes}
     * (0 disables this - never stale). Only ever consulted for a fully-unfilled offer (see the
     * {@code filled == 0} guard at this method's one call site in {@link #monitorOffers()}) - a
     * partial fill is never considered stale regardless of age, since aborting it would strand
     * the already-filled portion's exit strategy along with the cancelled remainder.
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
     * Aborts a stale, fully-unfilled offer whose slot is genuinely wanted by something else right
     * now (see {@link #isStale}'s javadoc for the "only if actually needed" gate this is called
     * under), and collects whatever comes back (nothing, since nothing filled - this is really
     * just freeing the GE slot) via {@code Rs2GrandExchange.cancelSpecificOffers}, which aborts
     * then internally collects in one call - no separate {@code collectOffer} needed afterward.
     * Deliberately does NOT requeue the order itself: per {@code staleOfferTimeoutMinutes}'s config
     * description, the point is to let the item go back through a fresh DECIDE tick and get
     * re-evaluated with the model's current judgment (spread/volatility/momentum/holding-duration),
     * not to blindly resubmit the same stale price - a hardcoded reprice-and-retry here would
     * defeat that purpose. The order is marked SKIPPED (an audit trail explaining why it vanished
     * from the queue) rather than left QUEUED, since re-queuing it verbatim would just recreate the
     * same stale price/quantity the model may no longer agree with.
     */
    private void abortStaleOffer(GrandExchangeSlots slot, PPOFlipperOrder order) {
        log.info("PPOFlipperStar: aborting stale unfilled offer in slot {} - {} (submitted {} min ago)",
            slot, order, (System.currentTimeMillis() - order.getSubmittedAtMillis()) / 60_000L);
        Rs2GrandExchange.cancelSpecificOffers(List.of(slot), config.collectToBank());
        markSkipped(order, "Aborted - stale, unfilled after " + config.staleOfferTimeoutMinutes() + " min");
        activeOrders.remove(slot);
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
