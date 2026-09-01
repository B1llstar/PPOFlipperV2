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
import java.util.Optional;

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
 *   <li>{@code accounts/{accountHash}/decision/request} - one transient doc, overwritten every
 *   decision tick, written by this class on behalf of the plugin (PROPOSAL.md §3.6/§4)</li>
 *   <li>{@code accounts/{accountHash}/decision/response} - one transient doc, overwritten by the
 *   Python inference worker; read (not written) by this class</li>
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
    // decision/request, decision/response - model<->plugin transport (PROPOSAL.md §3.6/§4)
    // ---------------------------------------------------------------------------------------

    /**
     * One per-watchlisted-item state vector packed into a {@code decision/request} write - field
     * names/shape here are exactly what {@code data/ppo/inference_worker.py}'s
     * {@code _build_observation_row}/{@code _action_to_order} expect on the Python side (see that
     * module's docstring). {@code marketFeatures} carries the 13 raw (pre-normalization)
     * {@code MARKET_FEATURE_COLUMNS} values - normalization (mean_price_* and volatility_* rescaled
     * against {@code midPrice}, volume_* log1p'd) happens on the Python side via
     * {@code env.py}'s own {@code _normalize_market_features}, not here, so the wire format stays
     * a plain mirror of whatever the plugin can compute rather than a second, easy-to-drift
     * reimplementation of that normalization math in Java.
     */
    public static final class DecisionRequestItem {
        public final int itemId;
        public final Map<String, Double> marketFeatures;
        public final double midPrice;
        public final double avgLowPrice;
        public final double avgHighPrice;
        public final double positionSizeNorm;
        public final double unrealizedPct;
        public final double holdingDuration;
        public final double limitHeadroomUsed;
        public final double availableGpNorm;
        public final double freeSlotsNorm;
        public final int buyLimit;
        public final int limitHeadroomQty;
        public final int heldQuantity;

        public DecisionRequestItem(int itemId, Map<String, Double> marketFeatures, double midPrice,
                                    double avgLowPrice, double avgHighPrice, double positionSizeNorm,
                                    double unrealizedPct, double holdingDuration, double limitHeadroomUsed,
                                    double availableGpNorm, double freeSlotsNorm, int buyLimit,
                                    int limitHeadroomQty, int heldQuantity) {
            this.itemId = itemId;
            this.marketFeatures = marketFeatures;
            this.midPrice = midPrice;
            this.avgLowPrice = avgLowPrice;
            this.avgHighPrice = avgHighPrice;
            this.positionSizeNorm = positionSizeNorm;
            this.unrealizedPct = unrealizedPct;
            this.holdingDuration = holdingDuration;
            this.limitHeadroomUsed = limitHeadroomUsed;
            this.availableGpNorm = availableGpNorm;
            this.freeSlotsNorm = freeSlotsNorm;
            this.buyLimit = buyLimit;
            this.limitHeadroomQty = limitHeadroomQty;
            this.heldQuantity = heldQuantity;
        }
    }

    /**
     * Overwrites the single {@code decision/request} document for this account (PROPOSAL.md §3.6:
     * "one document, overwritten every decision tick, not one-per-tick history") with a fresh
     * {@code tickId} and the full watchlisted-items state vector batch. Uses PUT-via-PATCH-with-no-
     * mask semantics (a plain PATCH with no {@code updateMask} query param replaces every field of
     * the document, unlike {@link #patchDocument} elsewhere in this class which always scopes a
     * mask) since the whole request doc is meant to be fully replaced every tick, not merged.
     */
    public void putDecisionRequest(long accountHash, long tickId, List<DecisionRequestItem> items)
        throws IOException, InterruptedException {
        JsonArray itemsArray = new JsonArray();
        for (DecisionRequestItem item : items) {
            JsonObject itemFields = new JsonObject();
            itemFields.add("itemId", integerValue(item.itemId));
            itemFields.add("marketFeatures", mapOfDoubleValues(item.marketFeatures));
            itemFields.add("midPrice", doubleValue(item.midPrice));
            itemFields.add("avgLowPrice", doubleValue(item.avgLowPrice));
            itemFields.add("avgHighPrice", doubleValue(item.avgHighPrice));
            itemFields.add("positionSizeNorm", doubleValue(item.positionSizeNorm));
            itemFields.add("unrealizedPct", doubleValue(item.unrealizedPct));
            itemFields.add("holdingDuration", doubleValue(item.holdingDuration));
            itemFields.add("limitHeadroomUsed", doubleValue(item.limitHeadroomUsed));
            itemFields.add("availableGpNorm", doubleValue(item.availableGpNorm));
            itemFields.add("freeSlotsNorm", doubleValue(item.freeSlotsNorm));
            itemFields.add("buyLimit", integerValue(item.buyLimit));
            itemFields.add("limitHeadroomQty", integerValue(item.limitHeadroomQty));
            itemFields.add("heldQuantity", integerValue(item.heldQuantity));

            JsonObject itemMapValue = new JsonObject();
            itemMapValue.add("fields", itemFields);
            JsonObject itemWrapper = new JsonObject();
            itemWrapper.add("mapValue", itemMapValue);
            itemsArray.add(itemWrapper);
        }

        JsonObject fields = new JsonObject();
        fields.add("tickId", integerValue(tickId));
        JsonObject itemsWrapper = new JsonObject();
        itemsWrapper.add("arrayValue", wrapArrayValues(itemsArray));
        fields.add("items", itemsWrapper);
        fields.add("writtenAt", timestampValueNow());

        JsonObject body = new JsonObject();
        body.add("fields", fields);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(accountRoot(accountHash) + "/decision/request"))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("put decision request failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    /** One proposed action from the model's {@code decision/response}, per PROPOSAL.md §3.6's {@code {itemId, action, quantity, price, confidence}} schema. */
    public static final class DecisionAction {
        public final int itemId;
        public final String action;
        public final int quantity;
        public final int price;
        public final double confidence;

        public DecisionAction(int itemId, String action, int quantity, int price, double confidence) {
            this.itemId = itemId;
            this.action = action;
            this.quantity = quantity;
            this.price = price;
            this.confidence = confidence;
        }
    }

    /** The full {@code decision/response} document, or null fields where the document doesn't exist yet (no inference worker has ever answered this account). */
    public static final class DecisionResponse {
        public final long tickId;
        public final List<DecisionAction> actions;
        public final String checkpointVersion;

        public DecisionResponse(long tickId, List<DecisionAction> actions, String checkpointVersion) {
            this.tickId = tickId;
            this.actions = actions;
            this.checkpointVersion = checkpointVersion;
        }
    }

    /**
     * Reads the current {@code decision/response} document, or {@link Optional#empty()} if no
     * inference worker has ever written one for this account yet (a 404 - not an error condition,
     * same "collection/document doesn't exist yet" stance {@link #listDocuments} takes). The
     * caller ({@link net.runelite.client.plugins.microbot.ppoflipperstar.PPOFlipperStarScript})
     * is responsible for comparing the returned {@code tickId} against the most recently sent
     * request's tickId and ignoring a stale/mismatched answer - this method just reads whatever
     * is there right now.
     */
    public Optional<DecisionResponse> getDecisionResponse(long accountHash) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(accountRoot(accountHash) + "/decision/response"))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("get decision response failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonObject document = new JsonParser().parse(response.body()).getAsJsonObject();
        JsonObject fields = document.getAsJsonObject("fields");
        if (fields == null) {
            return Optional.empty();
        }

        long tickId = getLong(fields, "tickId");
        String checkpointVersion = fields.has("checkpointVersion")
            ? fields.getAsJsonObject("checkpointVersion").get("stringValue").getAsString() : "unknown";

        List<DecisionAction> actions = new ArrayList<>();
        for (JsonElement el : readArrayValues(fields, "actions")) {
            try {
                JsonObject actionFields = el.getAsJsonObject().getAsJsonObject("mapValue").getAsJsonObject("fields");
                int itemId = getInt(actionFields, "itemId");
                String actionName = actionFields.getAsJsonObject("action").get("stringValue").getAsString();
                int quantity = getInt(actionFields, "quantity");
                int price = getInt(actionFields, "price");
                double confidence = actionFields.has("confidence")
                    ? actionFields.getAsJsonObject("confidence").get("doubleValue").getAsDouble()
                    : 0.0;
                actions.add(new DecisionAction(itemId, actionName, quantity, price, confidence));
            } catch (Exception e) {
                log.warn("PPOFlipperStar: skipping malformed action entry in decision/response - {}", e.getMessage());
            }
        }

        return Optional.of(new DecisionResponse(tickId, actions, checkpointVersion));
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

    private static JsonObject doubleValue(double d) {
        JsonObject v = new JsonObject();
        // Firestore's REST wire format wants doubleValue as a plain JSON number, not a quoted
        // string (unlike integerValue, which Firestore's REST API represents as a string to avoid
        // precision loss on int64 values - see
        // https://firebase.google.com/docs/firestore/reference/rest/v1/Value).
        v.addProperty("doubleValue", d);
        return v;
    }

    /** Firestore's mapValue wire shape is {"mapValue": {"fields": {...}}} - used here for the per-item marketFeatures sub-object, each value wrapped as a doubleValue. */
    private static JsonObject mapOfDoubleValues(Map<String, Double> values) {
        JsonObject fields = new JsonObject();
        if (values != null) {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                fields.add(entry.getKey(), doubleValue(entry.getValue()));
            }
        }
        JsonObject mapValue = new JsonObject();
        mapValue.add("fields", fields);
        JsonObject wrapper = new JsonObject();
        wrapper.add("mapValue", mapValue);
        return wrapper;
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
