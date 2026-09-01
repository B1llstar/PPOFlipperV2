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
 * <p><b>Correctness gap, stated plainly:</b> {@code data/ppo/env.py}'s observation is built from
 * {@code data/ppo/features.py}'s rolling-window features (spread_pct plus volatility/mean_price/
 * volume/momentum at 1h/6h/24h - 12-, 72-, and 288-block rolling aggregates over 5-minute-block
 * historical data) computed offline over {@code data/raw/5m/*.parquet}. The live Java plugin has
 * no equivalent rolling time-series store - only {@link WikiPriceClient}'s single most-recent
 * insta-buy/insta-sell snapshot per item, refreshed on demand. This class does NOT reimplement a
 * rolling-window history buffer in Java (a real feature, out of scope for this milestone - flagged
 * here rather than silently faked): every 1h/6h/24h-windowed feature is approximated from the same
 * single live snapshot, using it as a stand-in for the (unavailable) windowed mean/volatility/
 * volume/momentum -- see {@link #buildMarketFeatures} for exactly which fields are real vs.
 * approximated. This means the live observation the model sees is systematically less informative
 * than what it was trained/backtested on (it has no real sense of recent trend/volatility), which
 * is precisely the kind of live-vs-backtest mismatch PROPOSAL.md §3.7 says shadow mode exists to
 * surface before any GP is at risk - this is not a defect to silently work around, it is the
 * expected first finding of running shadow mode at all. A faithful fix would maintain a rolling
 * per-item price/volume history buffer fed by repeated {@link WikiPriceClient} polls (or a
 * dedicated historical-candle endpoint) inside the plugin; that is real, non-trivial future work,
 * not something to fake plausibly here.
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
    private final Rs2ItemManager itemManager = new Rs2ItemManager();
    private final WikiPriceClient wikiPriceClient = new WikiPriceClient();

    private final AtomicLong tickIdGenerator = new AtomicLong(0);

    @Inject
    public DecisionEngine(PortfolioManager portfolio, BuyLimitLedger buyLimitLedger, GoldManager goldManager,
                           WatchlistManager watchlistManager, PPOFlipperStarFirestoreSync firestoreSync) {
        this.portfolio = portfolio;
        this.buyLimitLedger = buyLimitLedger;
        this.goldManager = goldManager;
        this.watchlistManager = watchlistManager;
        this.firestoreSync = firestoreSync;
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

            long tickId = tickIdGenerator.incrementAndGet();
            List<PPOFlipperStarFirestoreClient.DecisionRequestItem> items = new ArrayList<>();
            for (int itemId : watchedIds) {
                buildRequestItem(itemId, maxActiveOffers).ifPresent(items::add);
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

        while (System.currentTimeMillis() < deadline) {
            Optional<PPOFlipperStarFirestoreClient.DecisionResponse> response = firestoreSync.getDecisionResponse();
            if (response.isPresent() && response.get().tickId == tickId) {
                PPOFlipperStarFirestoreClient.DecisionResponse r = response.get();
                return Optional.of(new DecisionResult(r.tickId, r.actions, r.checkpointVersion));
            }
            Thread.sleep(pollIntervalMillis);
        }

        log.info("PPOFlipperStar: no matching decision/response for tickId={} within {}ms, defaulting to HOLD this tick.", tickId, timeoutMillis);
        return Optional.empty();
    }

    /**
     * One watchlisted item's state vector, or {@link Optional#empty()} if live price data isn't
     * available for it right now (an item with no recent wiki trade data can't usefully be
     * scored - skipped for this tick rather than sent with fabricated zeros, matching
     * {@link WikiPriceClient#getLatestPrice}'s own "return null, let the caller decide" contract).
     */
    private Optional<PPOFlipperStarFirestoreClient.DecisionRequestItem> buildRequestItem(int itemId, int maxActiveOffers) {
        WikiPriceClient.Price price = wikiPriceClient.getLatestPrice(itemId);
        if (price == null || price.instaBuyPrice <= 0 || price.instaSellPrice <= 0) {
            return Optional.empty();
        }

        double avgHigh = price.instaBuyPrice;
        double avgLow = price.instaSellPrice;
        double midPrice = (avgHigh + avgLow) / 2.0;

        Map<String, Double> marketFeatures = buildMarketFeatures(avgHigh, avgLow, midPrice);

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

        ItemMappingData mapping = Rs2GrandExchange.getItemMappingData(itemId);
        int buyLimit = (mapping != null && mapping.hasTradeLimit()) ? mapping.getEffectiveTradeLimit() : 0;
        int alreadyBought = buyLimitLedger.quantityBoughtInWindow(itemId, System.currentTimeMillis());
        int headroom = Math.max(buyLimit - alreadyBought, 0);
        double limitHeadroomUsed = buyLimit > 0 ? (double) alreadyBought / buyLimit : 0.0;
        double positionSizeNorm = buyLimit > 0 ? (double) heldQuantity / buyLimit : 0.0;

        double availableGpNorm = goldManager.getTotalGold() / GP_NORMALIZATION_DENOMINATOR;
        int activeOffers = Rs2GrandExchange.getActiveOfferSlots().length;
        int freeSlots = Math.max(MAX_GE_SLOTS - activeOffers, 0);
        double freeSlotsNorm = freeSlots / (double) MAX_GE_SLOTS;

        return Optional.of(new PPOFlipperStarFirestoreClient.DecisionRequestItem(
            itemId, marketFeatures, midPrice, avgLow, avgHigh,
            positionSizeNorm, unrealizedPct, holdingDuration, limitHeadroomUsed,
            availableGpNorm, freeSlotsNorm, buyLimit, headroom, heldQuantity
        ));
    }

    /**
     * Approximates data/ppo/features.py's 13 rolling-window market features from a single live
     * Wiki insta-buy/insta-sell snapshot - see this class's javadoc for the full explanation of
     * why this is an approximation, not a faithful reproduction, and what a real fix looks like.
     * spread_pct is computed exactly the same way features.py does (it's genuinely a
     * point-in-time feature, not a rolling one, so this one has no approximation gap at all).
     * Every *_1h/_6h/_24h field reuses the same live snapshot for all three horizons since no
     * rolling history is available - volatility/momentum are set to 0 (a real "no signal"
     * value, consistent with clean_market_features.py's own fill-value for insufficient
     * history) rather than a fabricated nonzero number, mean_price_* is set to the current mid
     * price itself (net-zero effect after env.py's own normalization step, which rescales
     * mean_price_* to (mean_price / mid_price - 1) - exactly 0.0 here since they're equal, which
     * is a defensible "no trend information available" encoding), and volume_* is left as 0 since
     * the plugin has no historical volume figure at all here (only WikiPriceClient's price
     * fields, no volume).
     */
    private Map<String, Double> buildMarketFeatures(double avgHigh, double avgLow, double midPrice) {
        Map<String, Double> features = new LinkedHashMap<>();
        double spreadPct = avgLow > 0 ? (avgHigh - avgLow) / avgLow : 0.0;
        features.put("spread_pct", spreadPct);
        for (String window : new String[]{"1h", "6h", "24h"}) {
            features.put("volatility_" + window, 0.0);
            features.put("mean_price_" + window, midPrice);
            features.put("volume_" + window, 0.0);
            features.put("momentum_" + window, 0.0);
        }
        return features;
    }
}
