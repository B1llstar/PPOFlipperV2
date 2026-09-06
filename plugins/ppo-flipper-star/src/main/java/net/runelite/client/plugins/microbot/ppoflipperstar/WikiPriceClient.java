package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct client for the OSRS Wiki's real-time prices API (prices.runescape.wiki). Used by
 * {@link Guardrails}'s price-deviation check and {@link PPOFlipperStarScript}'s submission-time
 * price clamp - both need trustworthy live market data, not an average/lagging aggregator.
 *
 * <p><b>Why this exists instead of using {@code Rs2GrandExchange.getRealTimePrices}:</b> that
 * Microbot Hub utility sources its price from {@code ge-tracker.com}'s API first - a
 * third-party aggregator, not the wiki - and only falls back to the wiki if that call fails
 * outright. A sibling plugin in this repo (ge-star-v2) hit a live bug from exactly this: an
 * order clamped down to ~10gp for an item genuinely worth ~40gp, traced to a stale/wrong
 * ge-tracker price that {@code getRealTimePrices} trusted with no sanity check. This client
 * bypasses that path entirely and hits the wiki's {@code /latest} endpoint directly, same as
 * that fix.
 *
 * <p>Cached briefly per item (30s) since this can be called on every order submission tick and
 * the wiki's own data only updates on real trades anyway - no need to hit the API more often
 * than that.
 *
 * <p><b>Non-blocking by design:</b> {@link #getLatestPrice(int)} is a pure, instant cache read -
 * it never performs network I/O on the calling thread. A real incident found this client called
 * directly from {@code PPOFlipperStarScript}'s main tick thread (submission-time price clamp) and
 * from {@link Guardrails} (price-deviation check), each holding its own independent instance with
 * its own cold cache - unlike {@link DecisionEngine}'s copy, which warms itself via
 * {@link #refreshAllPrices()} before ever calling {@link #getLatestPrice(int)}, those two call
 * sites had no such warm-up and would fall through to a genuine synchronous HTTP round-trip (up to
 * the connect timeout) directly on the tick thread on every cache miss - stalling order
 * submission/guardrail checks, and with them every in-game GE action this script drives, for
 * however long that request took. A miss now kicks off a best-effort async refresh (deduped so
 * only one is ever in flight per item) and returns {@code null}/stale data immediately rather than
 * waiting on it - the next call, moments later, sees the warmed cache.
 */
@Slf4j
public class WikiPriceClient {

    private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest?id=%d";
    // Same endpoint with no id param - returns every tradeable item's latest price in one
    // response (confirmed live: a plain curl against this URL returns the full dataset). See
    // refreshAllPrices()'s javadoc for why this exists.
    private static final String LATEST_URL_ALL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    // Identifies this tool and a real, monitorable contact address per the wiki's own request
    // (see https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices) - a real incident is why
    // this carries a genuine contact rather than a vague placeholder: the previous string
    // ("OSRS-GE-Trading-Client/1.0 (contact: via GitHub)") ended up specifically, individually
    // blocked server-side (confirmed live via plain curl from an unrelated machine/network - a
    // generic/made-up User-Agent got 200, this exact string got 403) with no way to know why or
    // reach anyone about it. Most likely self-inflicted by the duplicate-header bug below before
    // it was fixed - repeated requests that looked like they came from two different clients
    // (this custom string AND java.net.http's own default) is exactly the kind of odd traffic
    // pattern the wiki's policy says can trigger a manual block. A real contact means the wiki
    // team (or anyone) can actually reach out if this client's traffic ever needs adjusting,
    // rather than a second silent, unrecoverable block down the line.
    //
    // Set via HttpRequest.Builder.setHeader(), NEVER .header() - JDK-8203771 documents that
    // .header() APPENDS rather than replaces, so a request built with .header("User-Agent", ...)
    // can carry BOTH java.net.http's own default "Java/<version>" header (which the wiki's policy
    // explicitly pre-emptively blocks, among other default HTTP-library signatures like
    // python-requests/curl/Apache-HttpClient) AND this custom one - which of the two a server
    // honors on a duplicate header is server/JDK-version-dependent, so this could work under one
    // JDK build and silently start getting blocked under another with zero code change (confirmed
    // live: worked initially on a fresh JDK 11 install on Windows, then started 403ing, while a
    // browser from the same machine/IP was unaffected throughout - ruling out a network/IP cause).
    // setHeader() explicitly clears any prior value for the key first, guaranteeing only the
    // intended value is ever sent.
    private static final String USER_AGENT = "PPOFlipperStar-RuneLite-Plugin/1.0 (contact: rumblingitscoming1@gmail.com)";
    private static final long CACHE_TTL_MILLIS = 30_000;

    // HTTP_1_1 forced explicitly, not left at HttpClient's default (which attempts HTTP/2 first) -
    // a real incident on Windows: identical requests (same URL, same User-Agent) succeeded from a
    // browser but got 403 from this client specifically. Cloudflare (which fronts the wiki's API)
    // is known to be more aggressive about flagging HTTP/2 connections with a non-browser
    // TLS/ALPN fingerprint than plain HTTP/1.1 ones - the exact fingerprint Java's HttpClient
    // presents can differ by JDK vendor/build, so this could pass on one machine's JDK and fail on
    // another's without any code difference at all. Forcing HTTP/1.1 sidesteps that fingerprinting
    // surface entirely; the wiki's API has no meaningful throughput need for HTTP/2's multiplexing
    // anyway (one small JSON response per call, no concurrent same-host requests worth pipelining).
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    // Separate AdaptiveTimeout per endpoint shape (bulk vs per-item) - see that class's javadoc.
    // A slow stretch on one shouldn't force the other to also assume it's slow.
    private static final AdaptiveTimeout BULK_TIMEOUT = new AdaptiveTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30));
    private static final AdaptiveTimeout PER_ITEM_TIMEOUT = new AdaptiveTimeout(Duration.ofSeconds(5), Duration.ofSeconds(15));

    // Shared by every WikiPriceClient instance (each caller - the script, Guardrails,
    // DecisionEngine - constructs its own) so a background refresh triggered by any one of them
    // warms the cache for all of them, and so at most one single-item async fetch is ever in
    // flight for a given item at a time regardless of how many instances ask for it concurrently.
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PPOFlipperStar-WikiPriceRefresh");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Integer, Boolean> FETCH_IN_FLIGHT = new ConcurrentHashMap<>();

    public static final class Price {
        public final int instaBuyPrice;
        public final int instaSellPrice;

        public Price(int instaBuyPrice, int instaSellPrice) {
            this.instaBuyPrice = instaBuyPrice;
            this.instaSellPrice = instaSellPrice;
        }
    }

    private static final class CachedPrice {
        final Price price;
        final long fetchedAtMillis;

        CachedPrice(Price price, long fetchedAtMillis) {
            this.price = price;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    private final Map<Integer, CachedPrice> cache = new ConcurrentHashMap<>();
    private volatile long lastBulkFetchAtMillis = 0;

    /**
     * Fetches EVERY item's latest price in one HTTP call (the wiki's {@code /latest} endpoint
     * with no {@code id} parameter returns the full dataset - confirmed live) and warms the cache
     * for all of them at once. Exists specifically for {@link DecisionEngine}'s per-tick loop over
     * every watchlisted item: calling {@link #getLatestPrice(int)} once per item there meant up to
     * ~300 sequential single-item HTTP requests per DECIDE tick, each with its own 5s timeout - a
     * real incident found live, where the wiki API had a slow/unreachable stretch and every one of
     * those 300 calls queued up and timed out one after another, stalling the DECIDE loop for
     * minutes and making autonomous trading look dead (the Python inference worker was actually
     * fine the whole time - this per-item fetch loop was the real bottleneck, not the model).
     *
     * <p>Call this ONCE per tick before the per-item loop, exactly the same fix shape as
     * {@code DecisionEngine.decide}'s own {@code Rs2GrandExchange.getActiveOfferSlots()} hoist -
     * see that method's javadoc for the sibling incident this mirrors. Respects the same
     * {@link #CACHE_TTL_MILLIS} as the per-item path via {@link #lastBulkFetchAtMillis}, so calling
     * this every tick is cheap when the cache is still warm (one no-op check, not a network call).
     * Never throws - a failed bulk fetch just leaves the cache as it was (individual
     * {@link #getLatestPrice(int)} calls still fall back to their own per-item fetch if needed).
     */
    public void refreshAllPrices() {
        if (System.currentTimeMillis() - lastBulkFetchAtMillis < CACHE_TTL_MILLIS) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_URL_ALL))
                .setHeader("User-Agent", USER_AGENT)
                .timeout(BULK_TIMEOUT.current())
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            BULK_TIMEOUT.onSuccess();
            if (response.statusCode() != 200) {
                log.warn("PPOFlipperStar: bulk wiki price fetch returned HTTP {}", response.statusCode());
                return;
            }

            JsonObject data = new JsonParser().parse(response.body()).getAsJsonObject().getAsJsonObject("data");
            if (data == null) {
                return;
            }

            long now = System.currentTimeMillis();
            int updated = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
                Price price = parsePrice(entry.getValue().getAsJsonObject());
                if (price == null) continue;
                try {
                    cache.put(Integer.parseInt(entry.getKey()), new CachedPrice(price, now));
                    updated++;
                } catch (NumberFormatException ignored) {
                    // Not expected (the wiki keys this map by item id), but never let a single
                    // malformed key break the rest of the batch.
                }
            }
            lastBulkFetchAtMillis = now;
            log.debug("PPOFlipperStar: bulk wiki price fetch updated {} item(s).", updated);
        } catch (java.net.http.HttpTimeoutException e) {
            BULK_TIMEOUT.onTimeout();
            log.warn("PPOFlipperStar: bulk wiki price fetch timed out (timeout now {}s after repeated slowness) - {}",
                BULK_TIMEOUT.current().getSeconds(), e.getMessage());
        } catch (Exception e) {
            log.warn("PPOFlipperStar: bulk wiki price fetch failed - {}", e.getMessage());
        }
    }

    private static Price parsePrice(JsonObject itemPrice) {
        // Same "high" (insta-buy)/"low" (insta-sell) convention as getLatestPrice - do not swap.
        Integer high = itemPrice.has("high") && !itemPrice.get("high").isJsonNull() ? itemPrice.get("high").getAsInt() : null;
        Integer low = itemPrice.has("low") && !itemPrice.get("low").isJsonNull() ? itemPrice.get("low").getAsInt() : null;
        return (high != null && low != null) ? new Price(high, low) : null;
    }

    /**
     * Live insta-buy/insta-sell reference for one item, or null if nothing is cached for it yet
     * (or the item has no recent trade data). <b>Never blocks and never performs network I/O on
     * the calling thread</b> - this is a pure, instant cache read. A cache miss (or an entry
     * older than {@link #CACHE_TTL_MILLIS}) triggers a best-effort async refresh on
     * {@link #BACKGROUND_EXECUTOR} (deduped via {@link #FETCH_IN_FLIGHT} so at most one fetch per
     * item is ever in flight at a time) and immediately returns whatever's cached right now - a
     * stale price if one exists, otherwise {@code null}. Callers on a real-time path (order
     * submission, guardrail checks) must already treat {@code null}/stale as "fall back to the
     * order's own price" per this method's pre-existing contract; the only change is that a miss
     * no longer stalls the caller waiting for the network to answer - it answers on a later call
     * once the background fetch lands, same as {@link #refreshAllPrices()}'s bulk warm-up already
     * does for {@link DecisionEngine}.
     */
    public Price getLatestPrice(int itemId) {
        CachedPrice cached = cache.get(itemId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
            return cached.price;
        }

        triggerBackgroundFetch(itemId);
        return cached != null ? cached.price : null;
    }

    private void triggerBackgroundFetch(int itemId) {
        if (FETCH_IN_FLIGHT.putIfAbsent(itemId, Boolean.TRUE) != null) {
            // Another call (from this instance or a sibling one - the executor/in-flight map are
            // shared statics) already has a fetch for this item in flight; don't pile up a second
            // one on top of it.
            return;
        }

        try {
            BACKGROUND_EXECUTOR.execute(() -> {
                try {
                    fetchOne(itemId);
                } finally {
                    FETCH_IN_FLIGHT.remove(itemId);
                }
            });
        } catch (Exception e) {
            // Executor rejected the task (e.g. shut down) - drop it, not fatal, just means this
            // item's cache stays stale/empty until the next call retries.
            FETCH_IN_FLIGHT.remove(itemId);
        }
    }

    /** The actual blocking HTTP call - runs only on {@link #BACKGROUND_EXECUTOR}, never on a caller's thread. */
    private void fetchOne(int itemId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(LATEST_URL, itemId)))
                .setHeader("User-Agent", USER_AGENT)
                .timeout(PER_ITEM_TIMEOUT.current())
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            PER_ITEM_TIMEOUT.onSuccess();
            if (response.statusCode() != 200) {
                log.warn("PPOFlipperStar: wiki price lookup for item {} returned HTTP {}", itemId, response.statusCode());
                return;
            }

            JsonObject data = new JsonParser().parse(response.body()).getAsJsonObject().getAsJsonObject("data");
            JsonObject itemPrice = data != null ? data.getAsJsonObject(String.valueOf(itemId)) : null;
            if (itemPrice == null) {
                return;
            }

            // The wiki's "high" is the most recent insta-buy trade (what buyers are currently
            // paying) and "low" is the most recent insta-sell trade (what sellers are currently
            // getting) - do not swap these. See parsePrice() - same convention, shared logic.
            Price price = parsePrice(itemPrice);
            if (price == null) {
                return;
            }

            cache.put(itemId, new CachedPrice(price, System.currentTimeMillis()));
        } catch (java.net.http.HttpTimeoutException e) {
            PER_ITEM_TIMEOUT.onTimeout();
            log.warn("PPOFlipperStar: wiki price lookup for item {} timed out (timeout now {}s after repeated slowness) - {}",
                itemId, PER_ITEM_TIMEOUT.current().getSeconds(), e.getMessage());
        } catch (Exception e) {
            log.warn("PPOFlipperStar: wiki price lookup failed for item {} - {}", itemId, e.getMessage());
        }
    }
}
