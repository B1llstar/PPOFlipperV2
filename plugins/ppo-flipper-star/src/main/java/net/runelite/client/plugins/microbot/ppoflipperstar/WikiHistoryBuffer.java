package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains a rolling per-item 5-minute price/volume history by polling the OSRS Wiki's bulk
 * {@code /5m} endpoint (one request returns every tradeable item's candle for the most recent
 * complete 5-minute block - the same endpoint and bulk-per-timestamp shape
 * {@code data/pipeline/fetch_5m_history.py} already uses to build this project's training data,
 * not looped per item, per the wiki's own guidance against per-item polling:
 * https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices).
 *
 * <p>This is the real fix for the gap {@link DecisionEngine}'s class javadoc documents: without
 * this buffer, the live plugin had no rolling time series at all and could only approximate
 * {@code data/ppo/features.py}'s 1h/6h/24h volatility/mean-price/volume/momentum features from a
 * single live snapshot (effectively "no signal," 0.0 for volatility/momentum/volume). With this
 * buffer accumulating real candles over time, {@link #computeRollingFeatures} reproduces
 * {@code build_features.py}'s {@code compute_rolling_features} formulas exactly (see that
 * method's javadoc for the one-for-one mapping) - the model sees genuinely comparable features
 * live to what it saw in training/backtesting, once the buffer has accumulated enough history
 * (see {@link #computeRollingFeatures}'s javadoc on cold-start behavior).
 *
 * <p>Polling is aligned to the wiki's own 5-minute block boundaries (fetching more often than
 * that just re-reads the same block - the wiki's {@code /5m} data only updates once per real
 * block) and lags one block behind "now" the same way {@code fetch_5m_history.py}'s
 * {@code latest_complete_block} does, since the most-recent block is sometimes still filling in
 * server-side.
 */
@Slf4j
@Singleton
public class WikiHistoryBuffer {

    private static final String FIVE_MIN_URL = "https://prices.runescape.wiki/api/v1/osrs/5m?timestamp=%d";
    private static final String USER_AGENT = "OSRS-GE-Trading-Client/1.0 (contact: via GitHub)";
    private static final long FIVE_MINUTES_SECONDS = 300;

    // 288 five-minute blocks = 24h, the largest rolling window features.py computes. Older
    // candles are evicted as new ones arrive - see addCandle.
    private static final int MAX_HISTORY_BLOCKS = 288;

    private static final int WINDOW_1H_BLOCKS = 12;
    private static final int WINDOW_6H_BLOCKS = 72;
    private static final int WINDOW_24H_BLOCKS = 288;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** One 5-minute candle, matching fetch_5m_history.py's fetch_block row shape exactly. */
    private static final class Candle {
        final long timestamp;
        final double avgHighPrice;
        final double highPriceVolume;
        final double avgLowPrice;
        final double lowPriceVolume;

        Candle(long timestamp, double avgHighPrice, double highPriceVolume, double avgLowPrice, double lowPriceVolume) {
            this.timestamp = timestamp;
            this.avgHighPrice = avgHighPrice;
            this.highPriceVolume = highPriceVolume;
            this.avgLowPrice = avgLowPrice;
            this.lowPriceVolume = lowPriceVolume;
        }

        double midPrice() {
            return (avgHighPrice + avgLowPrice) / 2.0;
        }
    }

    /** Rolling features for one window, mirroring features.py's per-window column set. */
    public static final class RollingFeatures {
        public final double volatility;
        public final double meanPrice;
        public final double volume;
        public final double momentum;

        RollingFeatures(double volatility, double meanPrice, double volume, double momentum) {
            this.volatility = volatility;
            this.meanPrice = meanPrice;
            this.volume = volume;
            this.momentum = momentum;
        }
    }

    private final Map<Integer, Deque<Candle>> history = new ConcurrentHashMap<>();
    private volatile long lastFetchedBlockTimestamp = -1;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Starts polling in the background if not already running. Safe to call repeatedly (e.g.
     * every plugin startUp) - a no-op if already started. Does an immediate first poll rather
     * than waiting for the first scheduled interval, so the buffer isn't empty for up to 5
     * minutes after the plugin starts.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PPOFlipperStar-WikiHistory");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, 1, TimeUnit.MINUTES);
        log.info("PPOFlipperStar: wiki 5m history buffer polling started.");
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * The most recent 5-minute block that should be fully aggregated by now - mirrors
     * fetch_5m_history.py's latest_complete_block exactly (back off one block from "now" since
     * the wiki's most-recent block can still be filling in server-side).
     */
    private static long latestCompleteBlock() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long block = nowSeconds - (nowSeconds % FIVE_MINUTES_SECONDS);
        return block - FIVE_MINUTES_SECONDS;
    }

    /**
     * Polled every minute (not every 5 minutes) so a block that just became available is picked
     * up within a minute of being ready, but {@link #lastFetchedBlockTimestamp} means the actual
     * HTTP call only fires once per genuinely new block - most polls are a no-op timestamp check.
     */
    private void pollOnce() {
        long block = latestCompleteBlock();
        if (block <= lastFetchedBlockTimestamp) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(FIVE_MIN_URL, block)))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("PPOFlipperStar: wiki 5m history poll for block {} returned HTTP {}", block, response.statusCode());
                return;
            }

            JsonObject payload = new JsonParser().parse(response.body()).getAsJsonObject();
            JsonObject data = payload.getAsJsonObject("data");
            if (data == null) {
                return;
            }

            int added = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                int itemId;
                try {
                    itemId = Integer.parseInt(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }

                JsonObject candleJson = entry.getValue().getAsJsonObject();
                Double avgHigh = doubleOrNull(candleJson, "avgHighPrice");
                Double highVol = doubleOrNull(candleJson, "highPriceVolume");
                Double avgLow = doubleOrNull(candleJson, "avgLowPrice");
                Double lowVol = doubleOrNull(candleJson, "lowPriceVolume");
                if (avgHigh == null || avgLow == null) {
                    // Matches build_features.py's own treatment of a block with no trade on one
                    // side - not every item trades every 5-minute block. Volumes default to 0
                    // (no volume that block) rather than skipping the candle entirely, matching
                    // fetch_5m_history.py's row shape (volume fields are nullable there too).
                    continue;
                }

                addCandle(itemId, new Candle(block, avgHigh, highVol != null ? highVol : 0.0,
                    avgLow, lowVol != null ? lowVol : 0.0));
                added++;
            }

            lastFetchedBlockTimestamp = block;
            log.debug("PPOFlipperStar: wiki 5m history poll fetched block {} - {} item candle(s) added.", block, added);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: wiki 5m history poll failed for block {} - {}", block, e.getMessage());
        }
    }

    private static Double doubleOrNull(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsDouble() : null;
    }

    private void addCandle(int itemId, Candle candle) {
        Deque<Candle> deque = history.computeIfAbsent(itemId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(candle);
            while (deque.size() > MAX_HISTORY_BLOCKS) {
                deque.removeFirst();
            }
        }
    }

    /** How many candles are currently buffered for an item - 0 if never seen. Useful for callers deciding whether there's enough history to trust a rolling feature yet. */
    public int historySize(int itemId) {
        Deque<Candle> deque = history.get(itemId);
        return deque == null ? 0 : deque.size();
    }

    /**
     * Computes the three rolling-window feature sets (1h/6h/24h) for one item from whatever
     * history has been accumulated so far, reproducing build_features.py's
     * compute_rolling_features formulas one-for-one:
     * <ul>
     *   <li>volatility_w = population std-dev of mid_price over the trailing w-block window
     *   (pandas' {@code .std()} default is the *sample* std-dev, ddof=1 - matched here, not
     *   population ddof=0, so a live value is comparable to what the training pipeline computed)</li>
     *   <li>mean_price_w = mean of mid_price over the trailing w-block window</li>
     *   <li>volume_w = sum of (high_price_volume + low_price_volume) over the trailing w-block window</li>
     *   <li>momentum_w = (mid_price_now - mid_price_w_blocks_ago) / mid_price_w_blocks_ago,
     *   i.e. pandas' {@code pct_change(periods=w)} - 0.0 if fewer than w+1 candles exist yet
     *   (pandas would produce NaN here; features.py's own downstream fillna(0) convention is
     *   matched by returning 0.0 directly rather than propagating a NaN through Firestore JSON)</li>
     * </ul>
     *
     * <p><b>Cold-start behavior</b> (a real, expected, and non-hidden limitation - not something
     * this method papers over): immediately after the plugin starts, or for an item just added to
     * the watchlist, there may be 0-287 candles buffered rather than a full 24h. volatility_24h/
     * mean_price_24h/volume_24h in particular need close to the full 288 blocks (24h ≈ 4.8 hours
     * of 1-minute-cadence polling landing new 5-minute blocks... actually 24h of wall-clock time,
     * since candles arrive one per 5-minute block regardless of poll frequency) to be a faithful
     * "last 24h" figure - until then, the computed value is genuinely "mean/volatility/volume over
     * however much history exists so far," matching pandas' {@code min_periods} semantics
     * (build_features.py uses {@code min_periods=1} for mean/volume, {@code min_periods=max(2,
     * window//4)} for volatility) rather than withholding the feature entirely. A freshly-added
     * watchlist item's first several hours of decision ticks will have thin 6h/24h windows -
     * this is inherent to needing real elapsed wall-clock time to build real history, not a bug
     * in this computation.
     */
    /**
     * @param fallbackMidPrice used as mean_price_w's value only when zero candles have been
     * buffered yet for this item (the true cold-start case) - returning 0.0 there instead would
     * normalize (in env.py's {@code mean_price / mid_price - 1}) to exactly -1.0, a strongly
     * misleading "price just crashed to zero" signal rather than the intended neutral "no trend
     * information available yet" (which is what equalling the live mid-price itself encodes,
     * since it normalizes to 0.0 - matching the old single-snapshot approximation's fallback
     * behavior for this one specific edge case, while every other case now uses real history).
     */
    public Map<String, RollingFeatures> computeRollingFeatures(int itemId, double fallbackMidPrice) {
        Deque<Candle> deque = history.get(itemId);
        Candle[] candles = deque != null ? deque.toArray(new Candle[0]) : new Candle[0];

        Map<String, RollingFeatures> result = new java.util.LinkedHashMap<>();
        result.put("1h", computeWindow(candles, WINDOW_1H_BLOCKS, fallbackMidPrice));
        result.put("6h", computeWindow(candles, WINDOW_6H_BLOCKS, fallbackMidPrice));
        result.put("24h", computeWindow(candles, WINDOW_24H_BLOCKS, fallbackMidPrice));
        return result;
    }

    private RollingFeatures computeWindow(Candle[] candles, int windowBlocks, double fallbackMidPrice) {
        int n = candles.length;
        if (n == 0) {
            return new RollingFeatures(0.0, fallbackMidPrice, 0.0, 0.0);
        }

        int start = Math.max(0, n - windowBlocks);
        int count = n - start;

        // mean_price_w: min_periods=1, matches build_features.py.
        double sumMid = 0.0;
        double sumVolume = 0.0;
        for (int i = start; i < n; i++) {
            sumMid += candles[i].midPrice();
            sumVolume += candles[i].highPriceVolume + candles[i].lowPriceVolume;
        }
        double meanPrice = sumMid / count;

        // volatility_w: sample std-dev (ddof=1, pandas' .std() default), min_periods=max(2,
        // window/4) - below that threshold there isn't enough history for a meaningful std-dev,
        // matching build_features.py's own "not enough data yet" gate rather than returning a
        // std-dev computed from too few points to mean anything.
        int minPeriods = Math.max(2, windowBlocks / 4);
        double volatility = 0.0;
        if (count >= minPeriods) {
            double sumSquaredDiff = 0.0;
            for (int i = start; i < n; i++) {
                double diff = candles[i].midPrice() - meanPrice;
                sumSquaredDiff += diff * diff;
            }
            volatility = Math.sqrt(sumSquaredDiff / (count - 1));
        }

        // momentum_w: pct_change(periods=w) - needs a candle exactly w blocks before the most
        // recent one; 0.0 (not NaN) if history is too thin, per this method's javadoc.
        double momentum = 0.0;
        int momentumIndex = n - 1 - windowBlocks;
        if (momentumIndex >= 0) {
            double midNow = candles[n - 1].midPrice();
            double midThen = candles[momentumIndex].midPrice();
            if (midThen != 0.0) {
                momentum = (midNow - midThen) / midThen;
            }
        }

        return new RollingFeatures(volatility, meanPrice, sumVolume, momentum);
    }
}
