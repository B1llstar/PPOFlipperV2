package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.BuyLimitLedger;
import net.runelite.client.plugins.microbot.ppoflipperstar.portfolio.PortfolioManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreClient;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
import net.runelite.client.plugins.microbot.util.item.Rs2ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the per-tick {@code decision/request} state vector for every watchlisted item (§3.2/§3.6
 * of PROPOSAL.md) from this plugin's own Managers, writes it via {@link PPOFlipperStarFirestoreSync},
 * and polls for the matching {@code decision/response} up to a configured timeout. Kept separate
 * from {@link PPOFlipperStarScript} so the (fairly involved) state-vector construction and its
 * documented approximations live in one focused place.
 *
 * <p><b>Live feature fidelity (previously a known gap, now fixed):</b> {@code data/ppo/env.py}'s
 * observation is built from {@code data/ppo/features.py}'s rolling-window features (spread_pct
 * plus volatility/mean_price/volume/momentum at 1h/6h/24h - 12-, 72-, and 288-block rolling
 * aggregates over 5-minute-block historical data) computed offline over
 * {@code data/raw/5m/*.parquet}. Earlier versions of this class approximated all of that from a
 * single live {@link WikiPriceClient} snapshot (volatility/momentum/volume flattened to 0, since
 * there was no rolling history to compute them from at all). {@link WikiHistoryBuffer} now polls
 * the same bulk wiki {@code /5m} endpoint the training data itself came from and accumulates a
 * real per-item rolling window, so {@link #buildMarketFeatures} computes genuine rolling features
 * via {@link WikiHistoryBuffer#computeRollingFeatures} rather than approximating them. The
 * remaining caveat is cold-start, not a permanent gap: a freshly-started plugin (or a
 * newly-watchlisted item) has a thin or empty history buffer until real wall-clock time passes to
 * fill it - see {@link WikiHistoryBuffer#computeRollingFeatures}'s javadoc for exactly how thin
 * windows are handled (matching {@code build_features.py}'s own {@code min_periods} semantics,
 * not fabricating a value). This is still a live-vs-backtest difference worth watching in shadow
 * mode per PROPOSAL.md §3.7, just a shrinking one rather than a fixed 0.0 forever.
 */
@Slf4j
@Singleton
public class DecisionEngine {

    /** Mirrors data/ppo/features.py's MARKET_FEATURE_COLUMNS order exactly - the Python worker reads this map by key, not by position, but keeping the same key set/spelling here avoids any drift. */
    private static final String[] MARKET_FEATURE_COLUMNS = {
        "spread_pct",
        "volatility_1h", "mean_price_1h", "volume_1h", "momentum_1h",
        "volatility_6h", "mean_price_6h", "volume_6h", "momentum_6h",
        "volatility_24h", "mean_price_24h", "volume_24h", "momentum_24h",
    };

    /** Mirrors data/ppo/env.py's EPISODE_LENGTH_BLOCKS-normalized holding-duration convention, but expressed in wall-clock terms since the live plugin has no notion of "episode blocks" - one simulated week (data/ppo/env.py's actual episode length) used as the normalization denominator so the resulting fraction is roughly comparable in scale to what the model saw in training. */
    private static final long HOLDING_DURATION_NORMALIZATION_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** Mirrors data/ppo/env.py's STARTING_GP - the denominator env.py divides available GP by to get its [0,1]-ish "availableGpNorm" global feature. Using the same constant here keeps the live feature on roughly the same scale the model trained on, though a live account's real starting GP obviously varies (see class javadoc's broader caveat about live-vs-backtest feature mismatch). */
    private static final double GP_NORMALIZATION_DENOMINATOR = 10_000_000.0;

    private static final int MAX_GE_SLOTS = 8;

    private final PortfolioManager portfolio;
    private final BuyLimitLedger buyLimitLedger;
    private final GoldManager goldManager;
    private final WatchlistManager watchlistManager;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final WikiHistoryBuffer wikiHistoryBuffer;
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final WikiPriceClient wikiPriceClient = new WikiPriceClient();

    private final AtomicLong tickIdGenerator = new AtomicLong(0);

    /**
     * True only when the most recent {@link #decide} call ended in {@link #pollForResponse}
     * timing out (a real request was written but no matching response ever arrived) - distinct
     * from the other reasons {@code decide} can return {@link Optional#empty()} (an empty
     * watchlist, sync disabled/no account hash yet), which aren't a sign anything is actually
     * wrong. Exists so {@code PPOFlipperStarScript} can tell "the model stopped responding" apart
     * from those benign cases and surface a real warning - see its own
     * {@code consecutiveDecideTimeouts} field/{@code maybeWarnModelUnresponsive} for why: a real
     * incident where the Python inference worker was killed and never restarted left the plugin
     * silently defaulting every tick to HOLD, visible only as a log line nobody was watching.
     */
    private volatile boolean lastDecideTimedOut = false;

    /** See {@link #lastDecideTimedOut}'s javadoc. */
    public boolean didLastDecideTimeOut() {
        return lastDecideTimedOut;
    }

    @Inject
    public DecisionEngine(PortfolioManager portfolio, BuyLimitLedger buyLimitLedger, GoldManager goldManager,
                           WatchlistManager watchlistManager, PPOFlipperStarFirestoreSync firestoreSync,
                           WikiHistoryBuffer wikiHistoryBuffer) {
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.goldManager = goldManager;
        this.watchlistManager = watchlistManager;
        this.firestoreSync = firestoreSync;
        this.wikiHistoryBuffer = wikiHistoryBuffer;
    }

    /** Result of one full decide-and-wait round, for the script to turn into panel suggestions. */
    public static final class DecisionResult {
        public final long tickId;
        public final List<PPOFlipperStarFirestoreClient.DecisionAction> actions;
        public final String checkpointVersion;

        DecisionResult(long tickId, List<PPOFlipperStarFirestoreClient.DecisionAction> actions, String checkpointVersion) {
            this.tickId = tickId;
            this.actions = actions;
            this.checkpointVersion = checkpointVersion;
        }
    }

    /**
     * Builds this tick's state vector for every watchlisted item, writes it to
     * {@code decision/request} with a fresh monotonic {@code tickId}, and short-polls for the
     * matching {@code decision/response} up to {@code timeoutMillis}. Returns
     * {@link Optional#empty()} on: an empty watchlist (nothing to decide), a failed/disabled
     * Firestore write, or a timeout with no matching response - every case the caller
     * (PPOFlipperStarScript) is expected to treat identically, per PROPOSAL.md §3.6: "a slow/
     * unreachable model must never block the trading loop," defaulting every item to HOLD for
     * that tick. Never throws - any unexpected failure is logged and treated as a timeout.
     */
    public Optional<DecisionResult> decide(long timeoutMillis, int maxActiveOffers) {
        try {
            Set<Integer> watchedIds = watchlistManager.getAll();
            if (watchedIds.isEmpty()) {
                return Optional.empty();
            }

            // Computed ONCE per tick, not per item - found live (bytecode-confirmed) that
            // Rs2GrandExchange.getActiveOfferSlots() does a genuine blocking round-trip onto the
            // RuneLite client thread per call (up to 8x internally, once per GrandExchangeSlots
            // value), with no internal caching. This value is a global fact ("how many GE slots
            // are active right now"), identical for every item in the same tick - calling it once
            // per watchlisted item (this was previously inside buildRequestItem, called in a loop
            // over the whole watchlist) meant a 300+-item watchlist queued thousands of blocking
            // client-thread round trips per second, which visibly froze the client's own render
            // loop through pure queue contention. See PROPOSAL.md/commit history for the full
            // incident - this was found via live testing, not anticipated in advance.
            double freeSlotsNorm = Math.max(MAX_GE_SLOTS - Rs2GrandExchange.getActiveOfferSlots().length, 0) / (double) MAX_GE_SLOTS;

            // Same fix shape as the getActiveOfferSlots() hoist just above - a real incident found
            // live: buildRequestItem's per-item wikiPriceClient.getLatestPrice(itemId) call meant
            // up to ~300 sequential single-item HTTP requests per tick, each with its own 5s
            // timeout. When the wiki API had a slow/unreachable stretch, every one of those 300
            // calls queued up and timed out one after another, stalling this whole method for
            // minutes and making autonomous trading look dead (the Python inference worker was
            // fine the whole time - this fetch loop was the actual bottleneck). One bulk call
            // upfront, cache-backed at the same TTL as the per-item path, so this is a cheap no-op
            // once warm rather than a real network call every tick.
            wikiPriceClient.refreshAllPrices();

            long tickId = tickIdGenerator.incrementAndGet();
            List<PPOFlipperStarFirestoreClient.DecisionRequestItem> items = new ArrayList<>();
            for (int itemId : watchedIds) {
                buildRequestItem(itemId, maxActiveOffers, freeSlotsNorm).ifPresent(items::add);
            }
            if (items.isEmpty()) {
                log.debug("PPOFlipperStar: no watchlisted item had usable live price data this tick, skipping decision request.");
                return Optional.empty();
            }

            boolean written = firestoreSync.pushDecisionRequest(tickId, items);
            if (!written) {
                log.debug("PPOFlipperStar: decision/request not written (sync disabled or no account hash yet), defaulting to HOLD this tick.");
                return Optional.empty();
            }

            return pollForResponse(tickId, timeoutMillis);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: DECIDE phase failed unexpectedly, defaulting to HOLD this tick - {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<DecisionResult> pollForResponse(long tickId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        long pollIntervalMillis = 300;
        // Reset at the start of every real polling attempt (a request was actually written) - see
        // lastDecideTimedOut's javadoc. Set true only if this specific attempt exhausts its
        // deadline below; a response arriving in time clears it back to false immediately.
        lastDecideTimedOut = false;

        while (System.currentTimeMillis() < deadline) {
            Optional<PPOFlipperStarFirestoreClient.DecisionResponse> response = firestoreSync.getDecisionResponse();
            if (response.isPresent() && response.get().tickId == tickId) {
                PPOFlipperStarFirestoreClient.DecisionResponse r = response.get();
                return Optional.of(new DecisionResult(r.tickId, r.actions, r.checkpointVersion));
            }
            Thread.sleep(pollIntervalMillis);
        }

        log.info("PPOFlipperStar: no matching decision/response for tickId={} within {}ms, defaulting to HOLD this tick.", tickId, timeoutMillis);
        lastDecideTimedOut = true;
        return Optional.empty();
    }

    /**
     * One watchlisted item's state vector, or {@link Optional#empty()} if live price data isn't
     * available for it right now (an item with no recent wiki trade data can't usefully be
     * scored - skipped for this tick rather than sent with fabricated zeros, matching
     * {@link WikiPriceClient#getLatestPrice}'s own "return null, let the caller decide" contract).
     */
    private Optional<PPOFlipperStarFirestoreClient.DecisionRequestItem> buildRequestItem(int itemId, int maxActiveOffers, double freeSlotsNorm) {
        WikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null || price.instaBuyPrice <= 0 || price.instaSellPrice <= 0) {
            return Optional.empty();
        }

        double avgHigh = price.instaBuyPrice;
        double avgLow = price.instaSellPrice;
        double midPrice = (avgHigh + avgLow) / 2.0;

        Map<String, Double> marketFeatures = buildMarketFeatures(itemId, avgHigh, avgLow, midPrice);

        int heldQuantity = portfolio.getHeldQuantity(itemId);
        long avgCost = portfolio.getAverageCost(itemId);
        double unrealizedPct = (heldQuantity > 0 && avgCost > 0) ? (midPrice - avgCost) / avgCost : 0.0;

        // No per-position acquisition-time read is exposed by PortfolioManager beyond
        // getAverageCost/getHeldQuantity - holdingDuration is approximated as 0 (matches env.py's
        // own "0 if not held" branch) unless a position is actually held, in which case an exact
        // duration isn't available from PortfolioManager's current public API. Documented here
        // rather than silently guessed: getOpenPositions() does expose CostBasisEntry (which has
        // getHoldingDurationMillis), used below when this item has an open position.
        double holdingDuration = portfolio.getOpenPositions().stream()
            .filter(e -> e.getItemId() == itemId)
            .findFirst()
            .map(e -> (double) e.getHoldingDurationMillis(System.currentTimeMillis()) / HOLDING_DURATION_NORMALIZATION_MILLIS)
            .orElse(0.0);

        // Deliberately reads tradeLimitPer4Hours directly, not hasTradeLimit()/
        // getEffectiveTradeLimit() - see Guardrails.checkBuyLimit's javadoc for the full story:
        // those two methods are a bytecode-confirmed bug/naming trap (hasTradeLimit() is really
        // "limit > 0 AND < 1000", getEffectiveTradeLimit() clamps anything >= 1000 to 500), which
        // silently zeroed buyLimit for the overwhelming majority of real tradeable items - this
        // is exactly what caused every live suggestion to come back with quantity=0 regardless of
        // confidence (see _action_to_order in inference_worker.py: desired_qty is 0 whenever
        // buyLimit <= 0). -1 is the field's real "no data" sentinel; anything else is genuine.
        ItemMappingData mapping = Rs2GrandExchange.getItemMappingData(itemId);
        int buyLimit = (mapping != null && mapping.tradeLimitPer4Hours > 0) ? mapping.tradeLimitPer4Hours : 0;
        int alreadyBought = buyLimitLedger.quantityBoughtInWindow(itemId, System.currentTimeMillis());
        int headroom = Math.max(buyLimit - alreadyBought, 0);
        double limitHeadroomUsed = buyLimit > 0 ? (double) alreadyBought / buyLimit : 0.0;
        double positionSizeNorm = buyLimit > 0 ? (double) heldQuantity / buyLimit : 0.0;

        double availableGpNorm = goldManager.getTotalGold() / GP_NORMALIZATION_DENOMINATOR;
        // freeSlotsNorm is now a parameter, computed once per tick by decide() - see that
        // method's comment for why this moved out of the per-item loop.

        return Optional.of(new PPOFlipperStarFirestoreClient.DecisionRequestItem(
            itemId, marketFeatures, midPrice, avgLow, avgHigh,
            positionSizeNorm, unrealizedPct, holdingDuration, limitHeadroomUsed,
            availableGpNorm, freeSlotsNorm, buyLimit, headroom, heldQuantity
        ));
    }

    /**
     * Builds data/ppo/features.py's 13 rolling-window market features from
     * {@link WikiHistoryBuffer}'s real accumulated per-item history. spread_pct is a genuinely
     * point-in-time feature (computed the same way features.py does, from the current live
     * snapshot, not the rolling buffer) - the *_1h/_6h/_24h fields come from
     * {@link WikiHistoryBuffer#computeRollingFeatures}, which reproduces
     * {@code compute_rolling_features}'s formulas exactly. See that method's javadoc for
     * cold-start behavior (thin/empty history shortly after startup or for a newly-watchlisted
     * item) - not something this method papers over, just inherent to needing real elapsed
     * wall-clock time to build real history.
     */
    private Map<String, Double> buildMarketFeatures(int itemId, double avgHigh, double avgLow, double midPrice) {
        Map<String, Double> features = new LinkedHashMap<>();
        double spreadPct = avgLow > 0 ? (avgHigh - avgLow) / avgLow : 0.0;
        features.put("spread_pct", spreadPct);

        Map<String, WikiHistoryBuffer.RollingFeatures> rolling = wikiHistoryBuffer.computeRollingFeatures(itemId, midPrice);
        for (String window : new String[]{"1h", "6h", "24h"}) {
            WikiHistoryBuffer.RollingFeatures f = rolling.get(window);
            features.put("volatility_" + window, f.volatility);
            features.put("mean_price_" + window, f.meanPrice);
            features.put("volume_" + window, f.volume);
            features.put("momentum_" + window, f.momentum);
        }
        return features;
    }
}
