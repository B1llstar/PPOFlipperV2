package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreClient;
import net.runelite.client.plugins.microbot.ppoflipperstar.sync.PPOFlipperStarFirestoreSync;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p><b>Persistence, added after a real bug found during live shadow-mode testing:</b> this
 * class used to be in-memory only. Since it's a Guice singleton scoped to the plugin's own
 * injector (which RuneLite recreates fresh on every disable/re-enable, not just a genuine client
 * restart), every re-enable silently wiped accumulated history back to empty - and because real
 * volatility/momentum/volume signal needs real elapsed wall-clock hours to build (see
 * {@link #computeRollingFeatures}'s cold-start note), a buffer that never survives more than a
 * few minutes at a time in practice NEVER accumulates real signal, no matter how long it's left
 * running in total. Confirmed live: a model fed all-zero volatility/momentum on every item
 * (because the buffer kept resetting) produced a badly-mispriced SELL suggestion that a guardrail
 * correctly rejected - the guardrail did its job, but the underlying cause was this class
 * silently losing its work. Two persistence layers now guard against that:
 * <ul>
 *   <li><b>Local {@code ConfigManager} cache</b> - the buffer's full state is persisted on every
 *   successful poll and restored on construction, so a same-machine plugin restart (disable/
 *   re-enable, or a client restart) resumes exactly where it left off instead of cold-starting.</li>
 *   <li><b>Shared Firestore {@code marketHistory/{itemId}}</b> - deliberately NOT under
 *   {@code accounts/{accountHash}/}, since this is public wiki market data, not per-account
 *   state (see {@link PPOFlipperStarFirestoreClient}'s marketHistory section). The first time
 *   this buffer needs history for an item it has no local cache for at all (a fresh install, or
 *   a newly-watchlisted item nobody's ever tracked on this machine), it pulls whatever's already
 *   there to seed itself instantly - useful today as a second line of defense if the local cache
 *   is ever lost, and becomes genuinely load-bearing once a second machine/account starts running
 *   this plugin against the same Firebase project, since it won't need to cold-start for hours
 *   either. Pushed periodically (not every poll), full-replace, best-effort - Firestore being
 *   unreachable never blocks local polling from working.</li>
 * </ul>
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

    private static final String CONFIG_GROUP = "ppoflipperstar";
    private static final String HISTORY_KEY = "wikiHistoryBuffer";
    private static final Type HISTORY_TYPE = new TypeToken<Map<Integer, List<Candle>>>() {}.getType();

    // How often to push the current buffer to the shared Firestore cache - not every poll (that
    // would be a write per watchlisted item every single minute, unnecessary for data that's
    // only ever read back at cold-start). Local ConfigManager persistence (every poll) is the
    // thing actually protecting against losing recent candles; this is just keeping the shared
    // cross-machine copy reasonably current.
    private static final long FIRESTORE_PUSH_INTERVAL_MINUTES = 10;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** One 5-minute candle, matching fetch_5m_history.py's fetch_block row shape exactly. Plain fields (no lombok) so Gson's default reflective (de)serialization round-trips it directly, same convention as PPOFlipperOrder - see OrderQueue's persistence javadoc for why that's safe for a final-field class with no no-args constructor. */
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

    private final ConfigManager configManager;
    private final PPOFlipperStarFirestoreSync firestoreSync;
    private final Gson gson = new Gson();

    private final Map<Integer, Deque<Candle>> history;
    // Items already checked against the shared Firestore cache (seeded from it or confirmed to
    // have their own local/live history already) - each item id is only ever seed-checked once
    // per plugin instance, on first need, not on every poll. See maybeSeedFromFirestore.
    private final Set<Integer> firestoreSeedChecked = ConcurrentHashMap.newKeySet();
    private volatile long lastFetchedBlockTimestamp = -1;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Inject
    public WikiHistoryBuffer(ConfigManager configManager, PPOFlipperStarFirestoreSync firestoreSync) {
        this.configManager = configManager;
        this.firestoreSync = firestoreSync;
        this.history = new ConcurrentHashMap<>(loadPersistedHistory());
    }

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
        scheduler.scheduleWithFixedDelay(this::pushAllToFirestore,
            FIRESTORE_PUSH_INTERVAL_MINUTES, FIRESTORE_PUSH_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("PPOFlipperStar: wiki 5m history buffer polling started ({} item(s) restored from local cache).",
            history.size());
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
            persistLocalHistory();
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

    // ---------------------------------------------------------------------------------------
    // Local ConfigManager persistence - see class javadoc's "Persistence" section for why this
    // exists. Written after every successful poll (addCandle already ran for every item in that
    // block by the time this is called); loaded once at construction.
    // ---------------------------------------------------------------------------------------

    private void persistLocalHistory() {
        Map<Integer, List<Candle>> snapshot = new HashMap<>();
        for (Map.Entry<Integer, Deque<Candle>> entry : history.entrySet()) {
            synchronized (entry.getValue()) {
                snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        configManager.setConfiguration(CONFIG_GROUP, HISTORY_KEY, gson.toJson(snapshot, HISTORY_TYPE));
    }

    private Map<Integer, Deque<Candle>> loadPersistedHistory() {
        String json = configManager.getConfiguration(CONFIG_GROUP, HISTORY_KEY);
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }

        Map<Integer, List<Candle>> restored;
        try {
            restored = gson.fromJson(json, HISTORY_TYPE);
        } catch (JsonSyntaxException e) {
            log.warn("PPOFlipperStar: wiki history buffer config was not valid JSON, starting empty - {}", e.getMessage());
            return new HashMap<>();
        }
        if (restored == null) {
            return new HashMap<>();
        }

        Map<Integer, Deque<Candle>> result = new HashMap<>();
        for (Map.Entry<Integer, List<Candle>> entry : restored.entrySet()) {
            result.put(entry.getKey(), new ArrayDeque<>(entry.getValue()));
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // Shared Firestore marketHistory/{itemId} - see class javadoc's "Persistence" section.
    // ---------------------------------------------------------------------------------------

    /**
     * Checks the shared Firestore cache for an item's history the FIRST time this buffer is
     * asked about it (an item with no local candles at all - a fresh install, or an item just
     * added to the watchlist that was never tracked on this machine before) and seeds from it if
     * found. Every subsequent call for the same item id is a no-op (tracked via
     * {@link #firestoreSeedChecked}) - once this machine has ANY local history for an item
     * (whether from a seed or its own live polling), there's no reason to keep re-checking
     * Firestore for it on every decision tick.
     */
    private void maybeSeedFromFirestore(int itemId) {
        if (!firestoreSeedChecked.add(itemId)) {
            return;
        }
        if (history.containsKey(itemId)) {
            return;
        }

        firestoreSync.pullMarketHistory(itemId).ifPresent(remote -> {
            int count = remote.timestamps.size();
            if (count == 0) return;

            Deque<Candle> deque = new ArrayDeque<>();
            for (int i = 0; i < count; i++) {
                deque.addLast(new Candle(
                    remote.timestamps.get(i),
                    i < remote.avgHighPrices.size() ? remote.avgHighPrices.get(i) : 0.0,
                    i < remote.highPriceVolumes.size() ? remote.highPriceVolumes.get(i) : 0.0,
                    i < remote.avgLowPrices.size() ? remote.avgLowPrices.get(i) : 0.0,
                    i < remote.lowPriceVolumes.size() ? remote.lowPriceVolumes.get(i) : 0.0));
            }
            while (deque.size() > MAX_HISTORY_BLOCKS) {
                deque.removeFirst();
            }

            history.put(itemId, deque);
            persistLocalHistory();
            log.info("PPOFlipperStar: seeded {} candle(s) for item {} from the shared Firestore market-history cache.",
                deque.size(), itemId);
        });
    }

    /**
     * Pushes every currently-tracked item's full candle buffer to the shared Firestore cache -
     * see {@link #FIRESTORE_PUSH_INTERVAL_MINUTES}. Best-effort per item; one failure doesn't
     * stop the rest from being pushed.
     */
    private void pushAllToFirestore() {
        for (Map.Entry<Integer, Deque<Candle>> entry : new HashMap<>(history).entrySet()) {
            int itemId = entry.getKey();
            Candle[] candles;
            synchronized (entry.getValue()) {
                candles = entry.getValue().toArray(new Candle[0]);
            }
            if (candles.length == 0) continue;

            List<Long> timestamps = new ArrayList<>(candles.length);
            List<Double> avgHighPrices = new ArrayList<>(candles.length);
            List<Double> highPriceVolumes = new ArrayList<>(candles.length);
            List<Double> avgLowPrices = new ArrayList<>(candles.length);
            List<Double> lowPriceVolumes = new ArrayList<>(candles.length);
            for (Candle c : candles) {
                timestamps.add(c.timestamp);
                avgHighPrices.add(c.avgHighPrice);
                highPriceVolumes.add(c.highPriceVolume);
                avgLowPrices.add(c.avgLowPrice);
                lowPriceVolumes.add(c.lowPriceVolume);
            }

            firestoreSync.pushMarketHistoryAsync(itemId, new PPOFlipperStarFirestoreClient.RemoteMarketHistory(
                timestamps, avgHighPrices, highPriceVolumes, avgLowPrices, lowPriceVolumes));
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
        maybeSeedFromFirestore(itemId);

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
