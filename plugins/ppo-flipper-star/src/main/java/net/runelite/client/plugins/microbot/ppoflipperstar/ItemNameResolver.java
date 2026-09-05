package net.runelite.client.plugins.microbot.ppoflipperstar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an item's display name to its canonical (unnoted) item id via the OSRS Wiki's static
 * {@code /mapping} data - genuinely a name-&gt;id database lookup, unlike
 * {@code Rs2ItemManager.getItemIdByName}.
 *
 * <p><b>Exists specifically because {@code Rs2ItemManager.getItemIdByName} is NOT a real
 * name-to-id lookup - a real, confirmed-live incident.</b> Decompiled its bytecode: it checks
 * {@code Rs2Bank.hasBankItem(name)} first, then {@code Rs2Inventory.hasItem(name)}, and returns
 * whichever live-held item's raw id it finds - only falling back to a genuine
 * {@code ItemManager.search(name)} database lookup if the name isn't currently held anywhere at
 * all. For a noted item genuinely sitting in inventory (exactly the case
 * {@link InventoryManager#canonicalItemId} needs to resolve), this means
 * {@code getItemIdByName} short-circuits on the inventory check and hands back the NOTED item's
 * own raw id, unchanged - not the unnoted id at all. Confirmed live: every noted item held across
 * an entire inventory (Yew longbow (u), Grapes, Ruby amulet, Sapphire ring, and more) resolved
 * this way, each one silently returning its own noted id back, which then failed every downstream
 * quantity check keyed by the true unnoted id (the wiki mapping's id, {@code order.getItemId()},
 * etc.) despite the item being genuinely, visibly held.
 *
 * <p>This class instead builds a plain name-&gt;id map from the wiki's {@code /mapping} endpoint
 * once (the exact same endpoint and bulk-fetch-not-per-item shape {@link DecisionEngine}'s own
 * {@code refreshItemMappings} already uses, for the same "the wiki has no per-item endpoint"
 * reason - see that method's javadoc) - since the wiki's mapping only ever lists a tradeable
 * item's real, GE-facing (unnoted) id, a name found there is unambiguously the correct unnoted id,
 * with no dependency on what happens to be sitting in the account's own inventory/bank right now.
 *
 * <p>Deliberately its OWN fetch/cache, not sharing {@link DecisionEngine}'s
 * {@code itemMappingCache} directly - that would require {@code InventoryManager} (used by
 * {@code PortfolioManager}, which {@code DecisionEngine} itself depends on) to depend on
 * {@code DecisionEngine}, a circular dependency. Both classes independently fetching the same
 * bulk endpoint on the same long TTL is a trivial, already-cheap cost (see
 * {@code ITEM_MAPPING_CACHE_TTL_MILLIS}'s own reasoning: mapping data changes "at most a few
 * times a year"), not worth the coupling risk to avoid.
 */
@Slf4j
@Singleton
public class ItemNameResolver {

    private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final String USER_AGENT = "PPOFlipperStar-RuneLite-Plugin/1.0 (contact: rumblingitscoming1@gmail.com)";
    // Same reasoning as DecisionEngine.ITEM_MAPPING_CACHE_TTL_MILLIS - name-to-id mappings change
    // "at most a few times a year", so this is already far more frequent than correctness needs.
    private static final long CACHE_TTL_MILLIS = 4L * 60 * 60 * 1000;
    // HTTP_1_1 forced explicitly - see WikiPriceClient.HTTP_CLIENT's javadoc for the real incident
    // this addresses (Cloudflare flagging Java's default HTTP/2 fingerprint as non-browser).
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private final Map<String, Integer> idByLowercaseName = new ConcurrentHashMap<>();
    private volatile long lastFetchAtMillis = 0;

    /**
     * The item's true, canonical (unnoted) id for this display name, or -1 if not found (an
     * untradeable item, an unrecognized name, or the fetch hasn't warmed the cache yet). Triggers
     * a synchronous refresh on first use or once the cache goes stale - deliberately blocking,
     * unlike {@link WikiPriceClient}'s async-refresh pattern: this is called rarely (only when
     * resolving a noted item's canonical id, not on every tick for every item), so a occasional
     * real network wait here is an acceptable tradeoff against the complexity of an async
     * cache-miss path for a call site that isn't performance-sensitive.
     */
    public int resolveId(String itemName) {
        if (itemName == null || itemName.isEmpty()) return -1;
        refreshIfStale();
        return idByLowercaseName.getOrDefault(itemName.toLowerCase(), -1);
    }

    private void refreshIfStale() {
        if (System.currentTimeMillis() - lastFetchAtMillis < CACHE_TTL_MILLIS && !idByLowercaseName.isEmpty()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MAPPING_URL))
                .setHeader("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("PPOFlipperStar: ItemNameResolver bulk mapping fetch returned HTTP {}", response.statusCode());
                return;
            }

            JsonArray entries = new JsonParser().parse(response.body()).getAsJsonArray();
            int count = 0;
            for (JsonElement element : entries) {
                JsonObject obj = element.getAsJsonObject();
                if (!obj.has("id") || !obj.has("name")) continue;
                idByLowercaseName.put(obj.get("name").getAsString().toLowerCase(), obj.get("id").getAsInt());
                count++;
            }
            lastFetchAtMillis = System.currentTimeMillis();
            log.info("PPOFlipperStar: ItemNameResolver warmed {} item name(s) from the wiki's mapping data.", count);
        } catch (Exception e) {
            log.warn("PPOFlipperStar: ItemNameResolver bulk mapping fetch failed - {}", e.getMessage());
        }
    }
}
