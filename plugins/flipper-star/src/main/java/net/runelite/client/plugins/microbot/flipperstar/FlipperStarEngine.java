package net.runelite.client.plugins.microbot.flipperstar;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scan/decide/queue logic: fetches ranked buy candidates and per-position sell decisions from
 * the scoring service, filters by config thresholds and current exposure, sizes orders, and
 * queues them into GE Star V2 via {@link GeStarBridge}. Buys are model-ranked candidates from
 * the entry margin model; sells are model-driven hold/sell decisions from the dedicated exit
 * model (see HANDOFF_FLIPPER_EXIT_MODEL.md for why a repurposed entry signal isn't used for
 * exits) - "hold" is simply not queuing a SELL, there's no separate hold action to take. Not a
 * {@code Script} (the base class most Hub plugins' game-loop logic extends) - scanning never
 * touches the game world (no widgets, no walking, no login state), it's pure HTTP + reflection,
 * so gating it on {@code Microbot.isLoggedIn()}/the client-thread conventions a real Script
 * needs would be dishonest about what this actually does. GE Star V2 (a real Script) is what
 * executes anything in-game.
 */
@Slf4j
@Singleton
public class FlipperStarEngine {

    private final ScoringServiceClient scoringServiceClient;
    private final GeStarBridge geStarBridge;

    @Getter
    private volatile String lastScanSummary = "Never scanned";

    @Getter
    private volatile long lastScanTimestamp = 0;

    @Getter
    private volatile List<Candidate> lastScanCandidates = List.of();

    @Getter
    private volatile String lastExitScanSummary = "Never scanned";

    @Getter
    private volatile long lastExitScanTimestamp = 0;

    // Order ids FlipperStar itself queued this session, so maxOpenFlips only counts flips it
    // originated - GE Star V2's queue may also hold orders added manually or from the web UI,
    // which shouldn't count against FlipperStar's own exposure cap. ConcurrentHashMap-backed
    // set (not a plain HashSet) because getOpenFlipCount() is read from the panel's Swing
    // Timer roughly every 2s while scanAndQueue() may be mutating this concurrently from a
    // background scan thread (manual Scan button) or the auto-scan executor.
    private final Set<Long> openOrderIds = ConcurrentHashMap.newKeySet();

    // Separate from openOrderIds - maxOpenFlips is a buy-specific exposure cap and shouldn't
    // be shared with sell tracking. Keyed by item id (not order id) so a position already has
    // a pending SELL is easy to check without a second lookup, and the order id is kept so
    // reconcileOrders can drop it once GE Star V2 reports it finished/failed/removed.
    private final Map<Integer, Long> pendingSellOrderIdsByItemId = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> autoScanTask;

    @Inject
    public FlipperStarEngine(ScoringServiceClient scoringServiceClient, GeStarBridge geStarBridge) {
        this.scoringServiceClient = scoringServiceClient;
        this.geStarBridge = geStarBridge;
    }

    /**
     * Runs one scan/queue pass synchronously - safe to call from a background thread off the
     * panel's Scan button, or from the auto-scan timer. Results are available afterward via
     * {@link #getLastScanCandidates()}/{@link #getLastScanSummary()} rather than a return
     * value, since the auto-scan timer also calls this on its own schedule with nothing
     * reading a return value.
     */
    public synchronized void scanAndQueue(FlipperStarConfig config) {
        if (!geStarBridge.isAvailable()) {
            lastScanSummary = "GE Star V2 not running - enable it first";
            log.warn("FlipperStar: {}", lastScanSummary);
            lastScanCandidates = List.of();
            return;
        }

        reconcileOrders(openOrderIds);

        // Buying is inventory-only now (see GeStarPortfolio/withdrawFromBank) - a BUY that
        // fills with nowhere for the items to land would just fail to collect, so skip queuing
        // new buys entirely once inventory has no free slots rather than queuing orders that
        // can't actually be received. Doesn't skip the exit scan below - a full inventory is
        // exactly when freeing space via a model-approved sell matters most, and this never
        // forces a sell the exit model wouldn't otherwise recommend on its own.
        if (Rs2Inventory.isFull()) {
            lastScanSummary = "Inventory full - skipping buy scan this cycle";
            log.info("FlipperStar: {}", lastScanSummary);
            lastScanCandidates = List.of();
            lastScanTimestamp = System.currentTimeMillis();
            if (config.exitScanEnabled()) {
                scanPositionsForExit(config);
            }
            return;
        }

        List<Candidate> candidates = scoringServiceClient.getCandidates(config.serviceUrl(), config.candidateLimit());
        lastScanCandidates = candidates;
        if (candidates.isEmpty()) {
            lastScanSummary = "Scoring service returned no candidates (is it running and reachable?)";
            log.warn("FlipperStar: {}", lastScanSummary);
            lastScanTimestamp = System.currentTimeMillis();
            return;
        }

        int queued = 0;
        int skippedMargin = 0;
        int skippedExposure = 0;
        int skippedAlreadyHeld = 0;
        int skippedNoSlots = 0;

        // Reserves one free inventory slot per new item this scan queues a BUY for, on top of
        // whatever's already committed to prior scans' still-open buys (openOrderIds - GE Star
        // V2 may have several of those actively submitted/queued at once, up to
        // maxActiveOffers, each landing in its own slot on fill). isFull() alone (checked
        // above) only catches an already-full inventory at the start of this scan - it doesn't
        // account for this same scan queuing more buys than there's room left for once earlier
        // orders in the loop have "claimed" a slot, which is exactly how active buy orders can
        // end up outnumbering free inventory slots.
        int projectedFreeSlots = Rs2Inventory.getEmptySlots() - openOrderIds.size();

        for (Candidate candidate : candidates) {
            if (openOrderIds.size() >= config.maxOpenFlips()) {
                skippedExposure++;
                continue;
            }

            if (candidate.getPredictedMarginPct() * 100 < config.minPredictedMarginPct()) {
                skippedMargin++;
                continue;
            }

            // Skip items already held from a prior flip that hasn't sold yet - avoid
            // compounding exposure to the same item before the existing position clears.
            if (geStarBridge.getHeldQuantity(candidate.getItemName()) > 0) {
                skippedAlreadyHeld++;
                continue;
            }

            if (projectedFreeSlots <= 0) {
                skippedNoSlots++;
                continue;
            }

            int quantity = sizeOrder(candidate, config.gpPerFlip());
            if (quantity <= 0) continue;

            int price = (int) Math.ceil(candidate.getCurrentBuyPrice());
            long orderId = geStarBridge.addOrder(GrandExchangeAction.BUY, candidate.getItemName(), quantity, price);
            if (orderId >= 0) {
                openOrderIds.add(orderId);
                queued++;
                projectedFreeSlots--;
                log.info("FlipperStar: queued BUY {}x {} @ {} (predicted margin {}%)",
                    quantity, candidate.getItemName(), price, candidate.getPredictedMarginPct() * 100);
            }
        }

        lastScanTimestamp = System.currentTimeMillis();
        lastScanSummary = String.format(
            "%d candidates, %d queued, %d below margin threshold, %d at exposure cap, %d already held, %d no free slots",
            candidates.size(), queued, skippedMargin, skippedExposure, skippedAlreadyHeld, skippedNoSlots);
        log.info("FlipperStar: scan complete - {}", lastScanSummary);

        if (config.exitScanEnabled()) {
            scanPositionsForExit(config);
        }
    }

    /**
     * Pulls every open position from GE Star V2, asks the scoring service's exit model whether
     * each should be sold, and queues a full-quantity SELL for anything it says to sell.
     * "Hold" has no explicit action - a position the model doesn't flag just isn't touched this
     * scan. Called from {@link #scanAndQueue} when {@link FlipperStarConfig#exitScanEnabled()}
     * is on, so "Scan" stays one user-facing action rather than needing a second auto-scan
     * timer.
     */
    public synchronized void scanPositionsForExit(FlipperStarConfig config) {
        if (!geStarBridge.isAvailable()) {
            lastExitScanSummary = "GE Star V2 not running - enable it first";
            log.warn("FlipperStar: {}", lastExitScanSummary);
            return;
        }

        reconcileOrders(pendingSellOrderIdsByItemId.values());

        List<OpenPosition> positions = geStarBridge.getOpenPositions();
        if (positions.isEmpty()) {
            lastExitScanSummary = "No open positions";
            lastExitScanTimestamp = System.currentTimeMillis();
            return;
        }

        List<SellDecision> decisions = scoringServiceClient.getShouldSellDecisions(
            config.serviceUrl(), config.shouldSellEndpointPath(), positions);
        if (decisions.isEmpty()) {
            lastExitScanSummary = "Scoring service returned no exit decisions (is it running, with an exit model loaded?)";
            log.warn("FlipperStar: {}", lastExitScanSummary);
            lastExitScanTimestamp = System.currentTimeMillis();
            return;
        }

        int sold = 0;
        int skippedAlreadyPending = 0;
        int skippedStalePosition = 0;
        for (SellDecision decision : decisions) {
            if (!decision.isSell()) continue;

            if (pendingSellOrderIdsByItemId.containsKey(decision.getItemId())) {
                skippedAlreadyPending++;
                continue;
            }

            OpenPosition position = findPosition(positions, decision.getItemId());
            if (position == null || position.getQuantityHeld() <= 0) continue;

            // GeStarPortfolio's cost-basis ledger only updates via a tracked GE Star V2 fill
            // (recordBuy/recordSell) - it has no way to notice an item leaving inventory some
            // other way (sold manually, banked, traded, lost). Cap the sell quantity to what's
            // actually in live inventory right now, not just what the ledger thinks is held -
            // without this, a fully-stale position (0 actually held) would still get queued as
            // a SELL that the unconditional sell-quantity guardrail then rejects every scan,
            // forever, since nothing here ever clears or corrects the stale ledger entry.
            int actuallyHeld = geStarBridge.getHeldQuantity(position.getItemName());
            int sellQuantity = Math.min(position.getQuantityHeld(), actuallyHeld);
            if (sellQuantity <= 0) {
                skippedStalePosition++;
                log.info("FlipperStar: skipping SELL for {} - ledger shows {}x held but inventory has none",
                    position.getItemName(), position.getQuantityHeld());
                continue;
            }

            // A reasonable starting ask - GE Star V2's clampToLivePrice floors any SELL up to
            // the true live insta-sell rate before submission regardless, so this doesn't need
            // to be precise, just in the right neighborhood.
            int price = (int) Math.floor(decision.getCurrentSellPrice());
            long orderId = geStarBridge.addOrder(
                GrandExchangeAction.SELL, position.getItemName(), sellQuantity, price);
            if (orderId >= 0) {
                pendingSellOrderIdsByItemId.put(decision.getItemId(), orderId);
                sold++;
                log.info("FlipperStar: queued SELL {}x {} @ {} ({})",
                    sellQuantity, position.getItemName(), price, decision);
            }
        }

        lastExitScanTimestamp = System.currentTimeMillis();
        lastExitScanSummary = String.format(
            "%d positions, %d SELL decisions queued, %d already pending, %d stale (not actually held)",
            positions.size(), sold, skippedAlreadyPending, skippedStalePosition);
        log.info("FlipperStar: exit scan complete - {}", lastExitScanSummary);
    }

    private static OpenPosition findPosition(List<OpenPosition> positions, int itemId) {
        for (OpenPosition position : positions) {
            if (position.getItemId() == itemId) return position;
        }
        return null;
    }

    /** Quantity capped by both the GP budget and the item's GE limit (if known) - never overspend the budget, never exceed what could actually be bought in one 4h window. */
    private int sizeOrder(Candidate candidate, int gpBudget) {
        if (candidate.getCurrentBuyPrice() <= 0) return 0;

        int byBudget = (int) (gpBudget / candidate.getCurrentBuyPrice());
        int quantity = byBudget;

        if (candidate.getGeLimit() != null && candidate.getGeLimit() > 0) {
            quantity = Math.min(quantity, candidate.getGeLimit());
        }

        return Math.max(quantity, 0);
    }

    /**
     * Drops any tracked order id that's no longer QUEUED or SUBMITTED in GE Star V2 (finished,
     * skipped, failed, or removed entirely) - called at the start of every scan so exposure
     * tracking (maxOpenFlips, and pending-sell-per-item) reflects real current state, not a
     * monotonically growing set of every order FlipperStar has ever queued this session. Used
     * for both openOrderIds directly and pendingSellOrderIdsByItemId's values() (a live view -
     * removing from it removes the corresponding item-id entry from the backing map too).
     */
    private void reconcileOrders(Collection<Long> orderIds) {
        orderIds.removeIf(orderId -> {
            String status = geStarBridge.getOrderStatusName(orderId);
            return status == null || (!status.equals("QUEUED") && !status.equals("SUBMITTED"));
        });
    }

    public int getOpenFlipCount() {
        return openOrderIds.size();
    }

    public int getPendingSellCount() {
        return pendingSellOrderIdsByItemId.size();
    }

    public synchronized void startAutoScan(FlipperStarConfig config) {
        stopAutoScan();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FlipperStar-AutoScan");
            t.setDaemon(true);
            return t;
        });
        long intervalSeconds = Math.max(1, config.autoScanIntervalSeconds());
        autoScanTask = executor.scheduleWithFixedDelay(
            () -> scanAndQueue(config), 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("FlipperStar: auto-scan started, every {} second(s)", intervalSeconds);
    }

    public synchronized void stopAutoScan() {
        if (autoScanTask != null) {
            autoScanTask.cancel(true);
            autoScanTask = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isAutoScanRunning() {
        return executor != null && !executor.isShutdown();
    }
}
