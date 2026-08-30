package net.runelite.client.plugins.microbot.flipperstar;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scan/decide/queue logic: fetches ranked candidates from the scoring service, filters by
 * config thresholds and current exposure, sizes a buy order per candidate, and queues it into
 * GE Star V2 via {@link GeStarBridge}. Not a {@code Script} (the base class most Hub plugins'
 * game-loop logic extends) - scanning never touches the game world (no widgets, no walking,
 * no login state), it's pure HTTP + reflection, so gating it on
 * {@code Microbot.isLoggedIn()}/the client-thread conventions a real Script needs would be
 * dishonest about what this actually does. GE Star V2 (a real Script) is what executes
 * anything in-game.
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

    // Order ids FlipperStar itself queued this session, so maxOpenFlips only counts flips it
    // originated - GE Star V2's queue may also hold orders added manually or from the web UI,
    // which shouldn't count against FlipperStar's own exposure cap. ConcurrentHashMap-backed
    // set (not a plain HashSet) because getOpenFlipCount() is read from the panel's Swing
    // Timer roughly every 2s while scanAndQueue() may be mutating this concurrently from a
    // background scan thread (manual Scan button) or the auto-scan executor.
    private final Set<Long> openOrderIds = ConcurrentHashMap.newKeySet();

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

        reconcileOpenOrders();

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

            int quantity = sizeOrder(candidate, config.gpPerFlip());
            if (quantity <= 0) continue;

            int price = (int) Math.ceil(candidate.getCurrentBuyPrice());
            long orderId = geStarBridge.addOrder(GrandExchangeAction.BUY, candidate.getItemName(), quantity, price);
            if (orderId >= 0) {
                openOrderIds.add(orderId);
                queued++;
                log.info("FlipperStar: queued BUY {}x {} @ {} (predicted margin {}%)",
                    quantity, candidate.getItemName(), price, candidate.getPredictedMarginPct() * 100);
            }
        }

        lastScanTimestamp = System.currentTimeMillis();
        lastScanSummary = String.format(
            "%d candidates, %d queued, %d below margin threshold, %d at exposure cap, %d already held",
            candidates.size(), queued, skippedMargin, skippedExposure, skippedAlreadyHeld);
        log.info("FlipperStar: scan complete - {}", lastScanSummary);
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
     * skipped, failed, or removed entirely) - called at the start of every scan so
     * maxOpenFlips reflects real current exposure, not a monotonically growing set of every
     * order FlipperStar has ever queued this session.
     */
    private void reconcileOpenOrders() {
        openOrderIds.removeIf(orderId -> {
            String status = geStarBridge.getOrderStatusName(orderId);
            return status == null || (!status.equals("QUEUED") && !status.equals("SUBMITTED"));
        });
    }

    public int getOpenFlipCount() {
        return openOrderIds.size();
    }

    public synchronized void startAutoScan(FlipperStarConfig config) {
        stopAutoScan();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FlipperStar-AutoScan");
            t.setDaemon(true);
            return t;
        });
        long intervalMinutes = Math.max(1, config.autoScanIntervalMinutes());
        autoScanTask = executor.scheduleWithFixedDelay(
            () -> scanAndQueue(config), 0, intervalMinutes, TimeUnit.MINUTES);
        log.info("FlipperStar: auto-scan started, every {} minute(s)", intervalMinutes);
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
