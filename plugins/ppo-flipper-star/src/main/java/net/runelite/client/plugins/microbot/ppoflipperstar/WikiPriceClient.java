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
 */
@Slf4j
public class WikiPriceClient {

    private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest?id=%d";
    // Same endpoint with no id param - returns every tradeable item's latest price in one
    // response (confirmed live: a plain curl against this URL returns the full dataset). See
    // refreshAllPrices()'s javadoc for why this exists.
    private static final String LATEST_URL_ALL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    // Deliberately generic, no personal/project-identifying terms - just enough to be a real
    // contact-identifying agent per the wiki's own request (see
    // https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices), without any obligation to
    // name this specific tool.
    private static final String USER_AGENT = "OSRS-GE-Trading-Client/1.0 (contact: via GitHub)";
    private static final long CACHE_TTL_MILLIS = 30_000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

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
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
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
     * Live insta-buy/insta-sell reference for one item, or null if the item has no recent
     * trade data or the request failed for any reason (network error, malformed response,
     * etc). Never throws - a failed lookup should fall back to the caller's own price/quantity
     * decision, not block it.
     */
    public Price getLatestPrice(int itemId) {
        CachedPrice cached = cache.get(itemId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
            return cached.price;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(LATEST_URL, itemId)))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("PPOFlipperStar: wiki price lookup for item {} returned HTTP {}", itemId, response.statusCode());
                return cached != null ? cached.price : null;
            }

            JsonObject data = new JsonParser().parse(response.body()).getAsJsonObject().getAsJsonObject("data");
            JsonObject itemPrice = data != null ? data.getAsJsonObject(String.valueOf(itemId)) : null;
            if (itemPrice == null) {
                return cached != null ? cached.price : null;
            }

            // The wiki's "high" is the most recent insta-buy trade (what buyers are currently
            // paying) and "low" is the most recent insta-sell trade (what sellers are currently
            // getting) - do not swap these. See parsePrice() - same convention, shared logic.
            Price price = parsePrice(itemPrice);
            if (price == null) {
                return cached != null ? cached.price : null;
            }

            cache.put(itemId, new CachedPrice(price, System.currentTimeMillis()));
            return price;
        } catch (Exception e) {
            log.warn("PPOFlipperStar: wiki price lookup failed for item {} - {}", itemId, e.getMessage());
            return cached != null ? cached.price : null;
        }
    }
}
