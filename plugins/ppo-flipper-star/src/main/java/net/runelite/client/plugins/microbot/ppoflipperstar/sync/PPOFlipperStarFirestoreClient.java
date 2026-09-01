package net.runelite.client.plugins.microbot.ppoflipperstar.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST client over PPOFlipperStar's own Firestore collections, all rooted at
 * {@code accounts/{accountHash}/...} (see {@link AccountIdentity} for how the account hash is
 * resolved). Plain java.net.http + Firestore's REST JSON document format, no Admin SDK -
 * independent reimplementation of the same technique as the sibling ge-star-v2 plugin's
 * {@code GeStarFirestoreClient} (deliberately not shared code/imports).
 *
 * <p>Collections (see PROPOSAL.md's Firestore-persistence addendum for the full schema):
 * <ul>
 *   <li>{@code accounts/{accountHash}/portfolio/{itemId}} - mirrors {@code CostBasisEntry}</li>
 *   <li>{@code accounts/{accountHash}/buyLimitLedger/{itemId}} - mirrors {@code BuyLimitLedger}'s per-item purchase-event list</li>
 *   <li>{@code accounts/{accountHash}/watchlist/{itemId}} - one doc per watched item id</li>
 *   <li>{@code accounts/{accountHash}/tradeHistory/{autoId}} - append-only fill log, auto-ID documents</li>
 * </ul>
 *
 * <p>Every method here is a synchronous blocking HTTP call - callers (see
 * {@link PPOFlipperStarFirestoreSync}) are responsible for running these off the calling
 * thread/script tick and treating any failure as best-effort/non-fatal, since local
 * enforcement/state must never depend on Firestore being reachable.
 */
@Slf4j
public class PPOFlipperStarFirestoreClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final PPOFlipperStarGoogleAuth auth;
    private final String documentsRootUrl;

    PPOFlipperStarFirestoreClient(PPOFlipperStarGoogleAuth auth) {
        this.auth = auth;
        this.documentsRootUrl = "https://firestore.googleapis.com/v1/projects/" + auth.projectId
            + "/databases/(default)/documents";
    }

    private String accountRoot(long accountHash) {
        return documentsRootUrl + "/accounts/" + accountHash;
    }

    // ---------------------------------------------------------------------------------------
    // portfolio/{itemId}
    // ---------------------------------------------------------------------------------------

    public static final class RemotePortfolioEntry {
        public final int itemId;
        public final int quantityHeld;
        public final long totalCostBasis;
        public final long realizedProfit;
        public final long weightedAcquisitionTimestampMillis;

        RemotePortfolioEntry(int itemId, int quantityHeld, long totalCostBasis, long realizedProfit,
                              long weightedAcquisitionTimestampMillis) {
            this.itemId = itemId;
            this.quantityHeld = quantityHeld;
            this.totalCostBasis = totalCostBasis;
            this.realizedProfit = realizedProfit;
            this.weightedAcquisitionTimestampMillis = weightedAcquisitionTimestampMillis;
        }
    }

    /** Full portfolio ledger snapshot for this account, keyed by item id. Empty map if the collection has no documents yet. */
    public Map<Integer, RemotePortfolioEntry> listPortfolio(long accountHash) throws IOException, InterruptedException {
        Map<Integer, RemotePortfolioEntry> result = new LinkedHashMap<>();
        for (JsonObject document : listDocuments(accountRoot(accountHash) + "/portfolio")) {
            try {
                JsonObject fields = document.getAsJsonObject("fields");
                int itemId = Integer.parseInt(docIdFromName(document));
                int quantityHeld = getInt(fields, "quantityHeld");
                long totalCostBasis = getLong(fields, "totalCostBasis");
                long realizedProfit = getLong(fields, "realizedProfit");
                long weightedAcquisitionTimestampMillis = getLong(fields, "weightedAcquisitionTimestampMillis");
                result.put(itemId, new RemotePortfolioEntry(itemId, quantityHeld, totalCostBasis, realizedProfit,
                    weightedAcquisitionTimestampMillis));
            } catch (Exception e) {
                log.warn("PPOFlipperStar: skipping malformed portfolio document - {}", e.getMessage());
            }
        }
        return result;
    }

    /** Overwrites the full state of one item's portfolio doc - the ledger's cost-basis math is authoritative locally, this just mirrors the resulting fields. */
    public void putPortfolioEntry(long accountHash, int itemId, int quantityHeld, long totalCostBasis, long realizedProfit,
                            long weightedAcquisitionTimestampMillis) throws IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        fields.add("quantityHeld", integerValue(quantityHeld));
        fields.add("averageCost", integerValue(quantityHeld > 0 ? (int) (totalCostBasis / quantityHeld) : 0));
        fields.add("totalCostBasis", integerValue(totalCostBasis));
        fields.add("realizedProfit", integerValue(realizedProfit));
        fields.add("weightedAcquisitionTimestampMillis", integerValue(weightedAcquisitionTimestampMillis));
        fields.add("updatedAt", timestampValueNow());

        String updateMask = "updateMask.fieldPaths=quantityHeld&updateMask.fieldPaths=averageCost"
            + "&updateMask.fieldPaths=totalCostBasis&updateMask.fieldPaths=realizedProfit"
            + "&updateMask.fieldPaths=weightedAcquisitionTimestampMillis&updateMask.fieldPaths=updatedAt";

        patchDocument(accountRoot(accountHash) + "/portfolio/" + itemId, fields, updateMask);
    }

    // ---------------------------------------------------------------------------------------
    // buyLimitLedger/{itemId}
    // ---------------------------------------------------------------------------------------

    public static final class RemoteBuyLimitEntry {
        public final int itemId;
        /** Flattened as parallel arrays rather than a nested array-of-objects - see putBuyLimitEntry's javadoc. */
        public final List<Integer> quantities;
        public final List<Long> timestampsMillis;

        RemoteBuyLimitEntry(int itemId, List<Integer> quantities, List<Long> timestampsMillis) {
            this.itemId = itemId;
            this.quantities = quantities;
            this.timestampsMillis = timestampsMillis;
        }
    }

    public Map<Integer, RemoteBuyLimitEntry> listBuyLimitLedger(long accountHash) throws IOException, InterruptedException {
        Map<Integer, RemoteBuyLimitEntry> result = new LinkedHashMap<>();
        for (JsonObject document : listDocuments(accountRoot(accountHash) + "/buyLimitLedger")) {
            try {
                JsonObject fields = document.getAsJsonObject("fields");
                int itemId = Integer.parseInt(docIdFromName(document));
                List<Integer> quantities = new ArrayList<>();
                List<Long> timestamps = new ArrayList<>();
                JsonArray qtyArray = readArrayValues(fields, "eventQuantities");
                JsonArray tsArray = readArrayValues(fields, "eventTimestampsMillis");
                for (JsonElement el : qtyArray) {
                    quantities.add(Integer.parseInt(el.getAsJsonObject().get("integerValue").getAsString()));
                }
                for (JsonElement el : tsArray) {
                    timestamps.add(Long.parseLong(el.getAsJsonObject().get("integerValue").getAsString()));
                }
                result.put(itemId, new RemoteBuyLimitEntry(itemId, quantities, timestamps));
            } catch (Exception e) {
                log.warn("PPOFlipperStar: skipping malformed buy-limit-ledger document - {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Overwrites the full rolling-window event list for one item. Stored as two parallel integer
     * arrays (quantities, timestamps) rather than an array of {quantity, timestamp} maps - both
     * are valid Firestore array shapes, parallel arrays keep the hand-built JSON on this side
     * simpler (one arrayValue-of-integerValue per field, no nested mapValue-in-arrayValue
     * wrapping) at the cost of the two arrays needing to stay index-aligned, which is fine since
     * this method always writes both from the same local list in the same order.
     */
    public void putBuyLimitEntry(long accountHash, int itemId, List<Integer> quantities, List<Long> timestampsMillis)
        throws IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        fields.add("eventQuantities", arrayOfIntegerValues(quantities));
        fields.add("eventTimestampsMillis", arrayOfLongValues(timestampsMillis));
        fields.add("updatedAt", timestampValueNow());

        String updateMask = "updateMask.fieldPaths=eventQuantities&updateMask.fieldPaths=eventTimestampsMillis"
            + "&updateMask.fieldPaths=updatedAt";

        patchDocument(accountRoot(accountHash) + "/buyLimitLedger/" + itemId, fields, updateMask);
    }

    // ---------------------------------------------------------------------------------------
    // watchlist/{itemId}
    // ---------------------------------------------------------------------------------------

    /** Every watched item id currently in Firestore for this account. */
    public List<Integer> listWatchlist(long accountHash) throws IOException, InterruptedException {
        List<Integer> result = new ArrayList<>();
        for (JsonObject document : listDocuments(accountRoot(accountHash) + "/watchlist")) {
            try {
                result.add(Integer.parseInt(docIdFromName(document)));
            } catch (Exception e) {
                log.warn("PPOFlipperStar: skipping malformed watchlist document - {}", e.getMessage());
            }
        }
        return result;
    }

    /** Adds (or no-ops if already present) one item id to the watchlist collection. */
    public void addWatchlistItem(long accountHash, int itemId) throws IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        fields.add("itemId", integerValue(itemId));
        fields.add("addedAt", timestampValueNow());

        JsonObject body = new JsonObject();
        body.add("fields", fields);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(accountRoot(accountHash) + "/watchlist/" + itemId))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("watchlist upsert failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    /** Removes one item id's watchlist document, if present. */
    public void removeWatchlistItem(long accountHash, int itemId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(accountRoot(accountHash) + "/watchlist/" + itemId))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        // Firestore returns 200 with an empty document body even if the document didn't exist -
        // deleting an already-absent doc is not an error condition here.
        if (response.statusCode() != 200) {
            throw new IOException("watchlist delete failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    // ---------------------------------------------------------------------------------------
    // tradeHistory/{autoId} - append-only, auto-ID documents
    // ---------------------------------------------------------------------------------------

    /**
     * Appends one immutable completed-fill record. Auto-ID (POST to the collection URL, no doc
     * id in the path) since this is a pure append-only log, never updated or looked up by id
     * afterward - matches the same auto-ID pattern ge-star-v2's {@code recordBuyEvent} uses for
     * {@code buyLimits/{agentId}/events}.
     */
    public void appendTradeHistory(long accountHash, String action, int itemId, String itemName, int quantity,
                             int pricePerUnit, long totalGp, long timestampMillis) throws IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        fields.add("action", stringValue(action));
        fields.add("itemId", integerValue(itemId));
        fields.add("itemName", stringValue(itemName));
        fields.add("quantity", integerValue(quantity));
        fields.add("pricePerUnit", integerValue(pricePerUnit));
        fields.add("totalGp", integerValue(totalGp));
        fields.add("timestampMillis", integerValue(timestampMillis));
        fields.add("recordedAt", timestampValueNow());

        JsonObject body = new JsonObject();
        body.add("fields", fields);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(accountRoot(accountHash) + "/tradeHistory"))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("append trade history failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    // ---------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------

    /** Lists every document directly under a collection URL (a plain GET on the collection, not runQuery - these collections are small enough per account that a full listing is fine). */
    private List<JsonObject> listDocuments(String collectionUrl) throws IOException, InterruptedException {
        List<JsonObject> documents = new ArrayList<>();
        String pageToken = null;

        do {
            String url = collectionUrl + "?pageSize=300" + (pageToken != null ? "&pageToken=" + pageToken : "");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // The parent "accounts/{hash}" document itself doesn't exist yet - an empty
                // subcollection listing, not an error (Firestore has no explicit collection
                // creation step; the first write to a doc under it creates the path implicitly).
                return documents;
            }
            if (response.statusCode() != 200) {
                throw new IOException("list documents failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
            if (json.has("documents")) {
                for (JsonElement el : json.getAsJsonArray("documents")) {
                    documents.add(el.getAsJsonObject());
                }
            }
            pageToken = json.has("nextPageToken") ? json.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);

        return documents;
    }

    private void patchDocument(String documentUrl, JsonObject fields, String updateMask)
        throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.add("fields", fields);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(documentUrl + "?" + updateMask))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("PATCH failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private static String docIdFromName(JsonObject document) {
        String name = document.get("name").getAsString();
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static int getInt(JsonObject fields, String key) {
        return fields.has(key) ? Integer.parseInt(fields.getAsJsonObject(key).get("integerValue").getAsString()) : 0;
    }

    private static long getLong(JsonObject fields, String key) {
        return fields.has(key) ? Long.parseLong(fields.getAsJsonObject(key).get("integerValue").getAsString()) : 0L;
    }

    private static JsonObject stringValue(String s) {
        JsonObject v = new JsonObject();
        v.addProperty("stringValue", s);
        return v;
    }

    private static JsonObject integerValue(int i) {
        JsonObject v = new JsonObject();
        v.addProperty("integerValue", i);
        return v;
    }

    private static JsonObject integerValue(long i) {
        JsonObject v = new JsonObject();
        v.addProperty("integerValue", i);
        return v;
    }

    private static JsonObject timestampValueNow() {
        JsonObject v = new JsonObject();
        v.addProperty("timestampValue", Instant.now().toString());
        return v;
    }

    private static JsonObject arrayOfIntegerValues(List<Integer> values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(integerValue(value));
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("arrayValue", wrapArrayValues(array));
        return wrapper;
    }

    private static JsonObject arrayOfLongValues(List<Long> values) {
        JsonArray array = new JsonArray();
        for (long value : values) {
            array.add(integerValue(value));
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("arrayValue", wrapArrayValues(array));
        return wrapper;
    }

    /** Firestore's arrayValue wire shape is {"arrayValue": {"values": [...]}}, one level deeper than a plain JSON array. */
    private static JsonObject wrapArrayValues(JsonArray array) {
        JsonObject values = new JsonObject();
        values.add("values", array);
        return values;
    }

    /** Unwraps a field previously written by {@link #arrayOfIntegerValues}/{@link #arrayOfLongValues} back into its plain JsonArray of value-wrapper objects. Missing/empty "values" (Firestore omits it entirely for a zero-length array) yields an empty array rather than throwing. */
    private static JsonArray readArrayValues(JsonObject fields, String key) {
        if (!fields.has(key)) return new JsonArray();
        JsonObject arrayValue = fields.getAsJsonObject(key).getAsJsonObject("arrayValue");
        if (arrayValue == null || !arrayValue.has("values")) return new JsonArray();
        return arrayValue.getAsJsonArray("values");
    }
}
