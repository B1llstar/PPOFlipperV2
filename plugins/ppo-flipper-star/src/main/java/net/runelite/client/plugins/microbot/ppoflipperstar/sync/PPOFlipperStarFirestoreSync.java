package net.runelite.client.plugins.microbot.ppoflipperstar.sync;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.ppoflipperstar.PPOFlipperStarConfig;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort background bridge between PPOFlipperStar's local {@code ConfigManager}-backed
 * state (portfolio ledger, buy-limit ledger, watchlist) and this account's Firestore documents
 * under {@code accounts/{accountHash}/...}. Mirrors the lifecycle/async-push pattern of the
 * sibling ge-star-v2 plugin's {@code GeStarFirestoreSync} - independent reimplementation, no
 * shared code.
 *
 * <p>Two directions, both deliberately asymmetric in how failure is handled:
 * <ul>
 *   <li><b>Startup pull</b> ({@link #pullAndReconcile}): runs once, when an account hash first
 *   becomes available, blocking the caller (expected to be called from a background thread, not
 *   the EDT or script tick) up to Firestore's own network timeout. Firestore wins as source of
 *   truth per this project's design decision - a successful pull overwrites local state for
 *   whatever it returns. A failed/unreachable pull logs a warning and leaves local state
 *   untouched so startup never blocks on cloud availability.</li>
 *   <li><b>Live pushes</b> ({@code pushXAsync} methods): fire-and-forget, submitted onto this
 *   class's own single-thread background executor so a slow/failed network call never blocks the
 *   calling thread (a script tick, the EDT). A failure here is logged and dropped - local state
 *   is already correct and persisted locally by the time these are called, Firestore is only
 *   ever catching up to it.</li>
 *   <li><b>Presence heartbeat</b> ({@link #pushPresenceHeartbeat}): a third, independent
 *   direction - refreshed on its own fixed schedule (not tied to any local mutation) so the
 *   Python inference worker can auto-discover which accounts are actively running the plugin
 *   without an account hash ever needing to be passed to it manually. See
 *   {@link PPOFlipperStarFirestoreClient#putPresence}.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class PPOFlipperStarFirestoreSync {

    // How often to refresh accounts/{hash}/presence/heartbeat while the plugin runs - the
    // Python inference worker (data/ppo/inference_worker.py) re-scans for active accounts on
    // roughly this same cadence, so an account that just started up is picked up within one
    // scan interval rather than needing the worker restarted.
    private static final long PRESENCE_HEARTBEAT_INTERVAL_SECONDS = 60;

    private final AccountIdentity accountIdentity;

    private volatile ExecutorService executor;
    private volatile ScheduledExecutorService presenceExecutor;
    private volatile PPOFlipperStarFirestoreClient client;
    private volatile boolean enabled;

    // Set by the plugin around its one-shot startup pullAndReconcile call, cleared when that
    // finishes (success, failure, or exception - always via a finally block at the call site).
    // PPOFlipperStarScript checks this before submitting any order: without it, clicking Execute
    // immediately after enabling the plugin could submit a trade against a stale/empty local
    // portfolio before the async pull lands, and that pull's Firestore-wins full-replace
    // reconcile would then clobber whatever the trade just recorded locally in the interim.
    private volatile boolean reconcilePending;

    @Inject
    public PPOFlipperStarFirestoreSync(AccountIdentity accountIdentity) {
        this.accountIdentity = accountIdentity;
    }

    /**
     * (Re)initializes the Firestore client from config and starts the background push executor.
     * Safe to call repeatedly (e.g. every plugin startUp) - tears down any previous executor
     * first. A no-op (sync stays disabled) if the config toggle is off, the configured service
     * account path is blank, or the key file can't be read/parsed - in every such case this logs
     * a warning and the plugin continues in local-only mode, never throwing.
     */
    public synchronized void start(PPOFlipperStarConfig config) {
        stop();

        if (!config.firestoreSyncEnabled()) {
            log.info("PPOFlipperStar: cloud sync disabled in config, running local-only.");
            return;
        }

        String pathText = config.firestoreServiceAccountPath();
        if (pathText == null || pathText.trim().isEmpty()) {
            log.warn("PPOFlipperStar: cloud sync enabled but no service account path configured, running local-only.");
            return;
        }

        Path path = Paths.get(pathText.trim());
        if (!Files.isRegularFile(path)) {
            log.error("PPOFlipperStar: service account file not found at {}, running local-only.", path);
            return;
        }

        try {
            PPOFlipperStarGoogleAuth auth = new PPOFlipperStarGoogleAuth(path);
            client = new PPOFlipperStarFirestoreClient(auth);
        } catch (Exception e) {
            log.error("PPOFlipperStar: failed to initialize Firestore auth, running local-only - {}", e.getMessage(), e);
            client = null;
            return;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PPOFlipperStar-FirestoreSync");
            t.setDaemon(true);
            return t;
        });
        enabled = true;

        presenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PPOFlipperStar-Presence");
            t.setDaemon(true);
            return t;
        });
        presenceExecutor.scheduleWithFixedDelay(
            this::pushPresenceHeartbeat, 0, PRESENCE_HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("PPOFlipperStar: cloud sync started.");
    }

    public synchronized void stop() {
        enabled = false;
        client = null;
        ExecutorService executorRef = executor;
        executor = null;
        if (executorRef != null) {
            executorRef.shutdownNow();
        }
        ScheduledExecutorService presenceExecutorRef = presenceExecutor;
        presenceExecutor = null;
        if (presenceExecutorRef != null) {
            presenceExecutorRef.shutdownNow();
        }
    }

    /**
     * Refreshes this account's presence heartbeat (see {@link PPOFlipperStarFirestoreClient#putPresence})
     * so the Python inference worker's account-discovery scan finds it - see PROPOSAL.md's
     * "auto-detects the account on login" design: the worker never needs an account hash passed
     * in manually, it lists {@code accounts/*} for documents with a recent heartbeat.
     *
     * <p>Deliberately uses {@link AccountIdentity#resolveBlocking} here, not the plain
     * non-blocking {@link AccountIdentity#getAccountHash} every other push method in this class
     * uses - found via live testing that they behave differently in a case that matters
     * specifically for this scheduled/repeating call: {@code AccountIdentity} is a Guice
     * singleton scoped to this plugin's own injector, which RuneLite recreates fresh every time
     * the plugin is disabled and re-enabled. {@code getAccountHash()} only ever has something to
     * report once {@code onGameStateChanged} has fired with {@code LOGGED_IN} on THIS instance -
     * but that event only fires on an actual login transition, never on plugin
     * re-registration while already logged in. A disable/re-enable cycle with no intervening
     * logout therefore left every scheduled heartbeat silently skipping forever (logged only at
     * debug, easy to miss), with no way to recover short of actually logging out and back in.
     * {@code resolveBlocking()} checks the client's real current login state directly (bounded to
     * a couple of seconds, never hangs) when the cache is empty, which correctly recovers from
     * exactly this case without needing a fresh transition event. The other {@code pushXAsync}
     * methods in this class don't need this fix: they only fire in response to a live local
     * mutation (a buy/sell recorded, a watchlist change) that can only happen after a real login
     * has already occurred in the current plugin instance's lifetime.
     */
    private void pushPresenceHeartbeat() {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return;

        Optional<Long> accountHash = accountIdentity.resolveBlocking();
        if (!accountHash.isPresent()) {
            log.debug("PPOFlipperStar: skipping presence heartbeat, no account hash resolved yet.");
            return;
        }

        try {
            // Not load-bearing for anything (the worker only cares about lastSeenMillis to
            // decide whether an account is actively running the plugin) - just a
            // nice-to-have diagnostic if it's ever useful to see which plugin version wrote a
            // given heartbeat. No manifest/build-versioning wiring exists in this Gradle module
            // to read a real version string at runtime, so this is deliberately a plain literal
            // rather than a fragile reflection-based lookup that would silently return null in a
            // shaded/sideloaded jar with no version metadata set.
            clientRef.putPresence(accountHash.get(), "1.0.0");
        } catch (Exception e) {
            log.debug("PPOFlipperStar: presence heartbeat failed - {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Called by the plugin right before starting the one-shot startup pull. */
    public void markReconcilePending() {
        reconcilePending = true;
    }

    /** Called by the plugin once the startup pull has finished, however it finished. */
    public void clearReconcilePending() {
        reconcilePending = false;
    }

    /**
     * True while a startup pull is in flight - {@link net.runelite.client.plugins.microbot.ppoflipperstar.PPOFlipperStarScript}
     * checks this before submitting any order (see {@link #reconcilePending}'s javadoc), holding
     * submission for a tick rather than trading against local state Firestore is about to
     * potentially overwrite.
     */
    public boolean isReconcilePending() {
        return reconcilePending;
    }

    // ---------------------------------------------------------------------------------------
    // Startup pull + reconcile
    // ---------------------------------------------------------------------------------------

    /** Result of a startup pull, one per collection - lets the caller reconcile each independently and log which (if any) fell back to local-only. */
    public static final class PullResult {
        public final Map<Integer, PPOFlipperStarFirestoreClient.RemotePortfolioEntry> portfolio;
        public final Map<Integer, PPOFlipperStarFirestoreClient.RemoteBuyLimitEntry> buyLimitLedger;
        public final List<Integer> watchlist;

        PullResult(Map<Integer, PPOFlipperStarFirestoreClient.RemotePortfolioEntry> portfolio,
                    Map<Integer, PPOFlipperStarFirestoreClient.RemoteBuyLimitEntry> buyLimitLedger,
                    List<Integer> watchlist) {
            this.portfolio = portfolio;
            this.buyLimitLedger = buyLimitLedger;
            this.watchlist = watchlist;
        }
    }

    /**
     * Pulls every collection for this account from Firestore, best-effort per collection (one
     * collection failing doesn't prevent the others from being returned). Returns
     * {@link Optional#empty()} only if sync isn't running at all (disabled, not yet started, or
     * no account hash resolved yet) - callers should treat that as "stay local-only for this
     * session" without logging it as an error, since it's the expected steady state when cloud
     * sync is off. Blocking - call from a background thread, never the EDT or a script tick.
     */
    public Optional<PullResult> pullAndReconcile() {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return Optional.empty();

        Optional<Long> accountHash = accountIdentity.resolveBlocking();
        if (!accountHash.isPresent()) {
            log.warn("PPOFlipperStar: cloud sync enabled but no account hash available yet (not logged in?), staying local-only for this session.");
            return Optional.empty();
        }
        long hash = accountHash.get();

        Map<Integer, PPOFlipperStarFirestoreClient.RemotePortfolioEntry> portfolio;
        try {
            portfolio = clientRef.listPortfolio(hash);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to pull portfolio from Firestore, keeping local state - {}", e.getMessage());
            portfolio = null;
        }

        Map<Integer, PPOFlipperStarFirestoreClient.RemoteBuyLimitEntry> buyLimitLedger;
        try {
            buyLimitLedger = clientRef.listBuyLimitLedger(hash);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to pull buy-limit ledger from Firestore, keeping local state - {}", e.getMessage());
            buyLimitLedger = null;
        }

        List<Integer> watchlist;
        try {
            watchlist = clientRef.listWatchlist(hash);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to pull watchlist from Firestore, keeping local state - {}", e.getMessage());
            watchlist = null;
        }

        return Optional.of(new PullResult(portfolio, buyLimitLedger, watchlist));
    }

    // ---------------------------------------------------------------------------------------
    // Live async pushes - one per local mutation point
    // ---------------------------------------------------------------------------------------

    public void pushPortfolioEntryAsync(int itemId, int quantityHeld, long totalCostBasis, long realizedProfit,
                                         long weightedAcquisitionTimestampMillis) {
        submit(hash -> client.putPortfolioEntry(hash, itemId, quantityHeld, totalCostBasis, realizedProfit,
            weightedAcquisitionTimestampMillis), "push portfolio entry for item " + itemId);
    }

    public void pushBuyLimitEntryAsync(int itemId, List<Integer> quantities, List<Long> timestampsMillis) {
        submit(hash -> client.putBuyLimitEntry(hash, itemId, quantities, timestampsMillis),
            "push buy-limit ledger entry for item " + itemId);
    }

    public void pushWatchlistAddAsync(int itemId) {
        submit(hash -> client.addWatchlistItem(hash, itemId), "push watchlist add for item " + itemId);
    }

    public void pushWatchlistRemoveAsync(int itemId) {
        submit(hash -> client.removeWatchlistItem(hash, itemId), "push watchlist remove for item " + itemId);
    }

    public void pushTradeHistoryAsync(String action, int itemId, String itemName, int quantity, int pricePerUnit,
                                       long totalGp, long timestampMillis) {
        submit(hash -> client.appendTradeHistory(hash, action, itemId, itemName, quantity, pricePerUnit, totalGp,
            timestampMillis), "push trade history entry for item " + itemId);
    }

    // ---------------------------------------------------------------------------------------
    // marketHistory/{itemId} - shared, NOT account-scoped (see PPOFlipperStarFirestoreClient's
    // marketHistory section for the full "why" - this is public wiki data, not per-account
    // state). Unlike every other method in this class, these don't need an account hash at all,
    // so they don't go through submit()/getDecisionResponse()'s account-hash-gated pattern.
    // ---------------------------------------------------------------------------------------

    /**
     * Blocking pull of one item's shared candle history - called once by {@link
     * net.runelite.client.plugins.microbot.ppoflipperstar.WikiHistoryBuffer} the first time it
     * needs history for an item it has no local cache for (a fresh local install, or a newly
     * watchlisted item), so it can seed itself instantly instead of needing real wall-clock hours
     * to rebuild rolling-window history from nothing. Returns {@link Optional#empty()} (never
     * throws) if sync is disabled or the read fails - the caller falls back to cold-starting from
     * zero exactly as if this method didn't exist, since Firestore being unreachable must never
     * block the plugin's own polling from working locally.
     */
    public Optional<PPOFlipperStarFirestoreClient.RemoteMarketHistory> pullMarketHistory(int itemId) {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return Optional.empty();

        try {
            PPOFlipperStarFirestoreClient.RemoteMarketHistory history = clientRef.getMarketHistory(itemId);
            return Optional.ofNullable(history);
        } catch (Exception e) {
            log.debug("PPOFlipperStar: failed to pull shared market history for item {} - {}", itemId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fire-and-forget push of one item's full current candle buffer, replacing whatever was
     * there. Called periodically (not on every poll) by {@code WikiHistoryBuffer} - best-effort,
     * same failure handling as every other async push in this class.
     */
    public void pushMarketHistoryAsync(int itemId, PPOFlipperStarFirestoreClient.RemoteMarketHistory history) {
        PPOFlipperStarFirestoreClient clientRef = client;
        ExecutorService executorRef = executor;
        if (clientRef == null || executorRef == null || executorRef.isShutdown()) return;

        try {
            executorRef.execute(() -> {
                try {
                    clientRef.putMarketHistory(itemId, history);
                } catch (Exception e) {
                    log.warn("PPOFlipperStar: failed to push shared market history for item {} - {}", itemId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("PPOFlipperStar: could not schedule market history push for item {} - {}", itemId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // modelTrainedItems/{gitCommit} - shared, NOT account-scoped, same reasoning as
    // marketHistory/{itemId} above. Read-only from the plugin's side; the training pipeline is
    // what writes it (out of scope for this plugin).
    // ---------------------------------------------------------------------------------------

    /**
     * Blocking pull of the trained-item list for one checkpoint's git commit - backs the panel's
     * explicit "Seed watchlist from trained items" button (never called automatically on startup,
     * see {@code PPOFlipperStarPanel}). Returns {@link Optional#empty()} (never throws) if sync is
     * disabled, no such document exists, or the read fails - the caller should show the user a
     * clear "couldn't load trained items" message rather than silently doing nothing.
     */
    public Optional<List<PPOFlipperStarFirestoreClient.TrainedItem>> pullModelTrainedItems(String gitCommit) {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return Optional.empty();

        try {
            return clientRef.getModelTrainedItems(gitCommit);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to pull modelTrainedItems for commit {} - {}", gitCommit, e.getMessage());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------------------------
    // decision/request, decision/response - model<->plugin transport (PROPOSAL.md §3.6)
    // ---------------------------------------------------------------------------------------
    //
    // Deliberately synchronous (unlike the pushXAsync methods above), not fire-and-forget: the
    // DECIDE phase in PPOFlipperStarScript needs to know the write actually landed before it
    // starts waiting/polling for a matching decision/response - firing this async and moving on
    // immediately would make "wait up to the timeout for a response" race against a write that
    // hasn't even happened yet. Callers (the script's own tick thread, never the EDT) are
    // expected to bound their own wait around these calls the same way any other Firestore call
    // in this sync layer is treated as best-effort/non-fatal.

    /**
     * Writes this tick's full watchlisted-item state vector batch to
     * {@code decision/request}, replacing whatever was there before. Returns false (never
     * throws) if sync isn't enabled or no account hash is available yet - callers should treat
     * that exactly like "no request sent this tick," i.e. skip to a HOLD default the same way a
     * response timeout would.
     */
    public boolean pushDecisionRequest(long tickId, List<PPOFlipperStarFirestoreClient.DecisionRequestItem> items) {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return false;

        Optional<Long> accountHash = accountIdentity.getAccountHash();
        if (!accountHash.isPresent()) {
            log.debug("PPOFlipperStar: skipping decision/request write, no account hash resolved yet.");
            return false;
        }

        try {
            clientRef.putDecisionRequest(accountHash.get(), tickId, items);
            return true;
        } catch (Exception e) {
            log.warn("PPOFlipperStar: failed to write decision/request (tickId={}) - {}", tickId, e.getMessage());
            return false;
        }
    }

    /**
     * Reads the current {@code decision/response} document, or {@link Optional#empty()} if sync
     * isn't enabled, no account hash is available, no worker has ever answered this account, or
     * the read itself failed - every such case is treated identically by the caller (keep
     * waiting, or time out to HOLD), so this collapses them all to empty rather than
     * distinguishing "not present" from "error" the way {@link PPOFlipperStarFirestoreClient#getDecisionResponse}
     * itself does for its own caller (this method).
     */
    public Optional<PPOFlipperStarFirestoreClient.DecisionResponse> getDecisionResponse() {
        PPOFlipperStarFirestoreClient clientRef = client;
        if (clientRef == null) return Optional.empty();

        Optional<Long> accountHash = accountIdentity.getAccountHash();
        if (!accountHash.isPresent()) return Optional.empty();

        try {
            return clientRef.getDecisionResponse(accountHash.get());
        } catch (Exception e) {
            log.debug("PPOFlipperStar: failed to read decision/response - {}", e.getMessage());
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface FirestoreOperation {
        void run(long accountHash) throws Exception;
    }

    /**
     * Submits one Firestore write onto the background executor, resolving the account hash from
     * the already-cached value ({@link AccountIdentity#getAccountHash()}, non-blocking) at
     * submit time. Silently drops the push (not even queued) if sync isn't running or no account
     * hash is cached yet - the write is not retried once the hash later resolves, since the next
     * equivalent local mutation (which always accompanies a call to one of these methods) will
     * naturally re-push with then-current state anyway.
     */
    private void submit(FirestoreOperation operation, String description) {
        PPOFlipperStarFirestoreClient clientRef = client;
        ExecutorService executorRef = executor;
        if (clientRef == null || executorRef == null || executorRef.isShutdown()) return;

        Optional<Long> accountHash = accountIdentity.getAccountHash();
        if (!accountHash.isPresent()) {
            log.debug("PPOFlipperStar: skipping cloud sync ({}), no account hash resolved yet.", description);
            return;
        }
        long hash = accountHash.get();

        try {
            executorRef.execute(() -> {
                try {
                    operation.run(hash);
                } catch (Exception e) {
                    log.warn("PPOFlipperStar: failed to {} - {}", description, e.getMessage());
                }
            });
        } catch (Exception e) {
            // Executor rejected the task (e.g. shut down between the isShutdown() check above
            // and this call) - best-effort, drop it rather than propagate to the caller.
            log.debug("PPOFlipperStar: could not schedule cloud sync ({}) - {}", description, e.getMessage());
        }
    }
}
