package net.runelite.client.plugins.microbot.gestarv2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin REST client over the Firestore "orders" collection, matching the schema the web UI's
 * Cloud Functions write (see firebase/functions/src/orders.ts - field names and status values
 * must stay in lockstep with that file). Plain java.net.http + Firestore's REST JSON document
 * format, no Admin SDK - see GoogleServiceAccountAuth for why.
 */
@Slf4j
class GeStarFirestoreClient {

    private static final String ORDERS_COLLECTION = "orders";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final GoogleServiceAccountAuth auth;
    private final String baseUrl;

    GeStarFirestoreClient(GoogleServiceAccountAuth auth) {
        this.auth = auth;
        this.baseUrl = "https://firestore.googleapis.com/v1/projects/" + auth.projectId
            + "/databases/(default)/documents/" + ORDERS_COLLECTION;
    }

    static final class RemoteOrder {
        final String docId;
        final GrandExchangeAction action;
        final String itemName;
        final int quantity;
        final int price;

        RemoteOrder(String docId, GrandExchangeAction action, String itemName, int quantity, int price) {
            this.docId = docId;
            this.action = action;
            this.itemName = itemName;
            this.quantity = quantity;
            this.price = price;
        }
    }

    /** Lists documents currently in status QUEUED - these are new orders the web UI submitted that haven't been picked up locally yet. */
    List<RemoteOrder> listQueuedOrders() throws IOException, InterruptedException {
        JsonObject structuredQuery = new JsonObject();
        JsonObject from = new JsonObject();
        from.addProperty("collectionId", ORDERS_COLLECTION);
        JsonArray fromArray = new JsonArray();
        fromArray.add(from);
        structuredQuery.add("from", fromArray);

        JsonObject fieldFilter = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("fieldPath", "status");
        fieldFilter.add("field", field);
        fieldFilter.addProperty("op", "EQUAL");
        JsonObject value = new JsonObject();
        value.addProperty("stringValue", "QUEUED");
        fieldFilter.add("value", value);
        JsonObject where = new JsonObject();
        where.add("fieldFilter", fieldFilter);
        structuredQuery.add("where", where);

        JsonObject body = new JsonObject();
        body.add("structuredQuery", structuredQuery);

        String parent = "https://firestore.googleapis.com/v1/projects/" + auth.projectId
            + "/databases/(default)/documents";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(parent + ":runQuery"))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("runQuery failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        List<RemoteOrder> results = new ArrayList<>();
        JsonArray rows = new JsonParser().parse(response.body()).getAsJsonArray();
        for (JsonElement rowEl : rows) {
            JsonObject row = rowEl.getAsJsonObject();
            if (!row.has("document")) continue;
            JsonObject document = row.getAsJsonObject("document");
            RemoteOrder order = parseOrderDocument(document);
            if (order != null) {
                results.add(order);
            }
        }
        return results;
    }

    private RemoteOrder parseOrderDocument(JsonObject document) {
        try {
            String name = document.get("name").getAsString();
            String docId = name.substring(name.lastIndexOf('/') + 1);
            JsonObject fields = document.getAsJsonObject("fields");

            String actionStr = fields.getAsJsonObject("action").get("stringValue").getAsString();
            GrandExchangeAction action = GrandExchangeAction.valueOf(actionStr);
            String itemName = fields.getAsJsonObject("itemName").get("stringValue").getAsString();
            int quantity = Integer.parseInt(fields.getAsJsonObject("quantity").get("integerValue").getAsString());
            int price = Integer.parseInt(fields.getAsJsonObject("price").get("integerValue").getAsString());

            return new RemoteOrder(docId, action, itemName, quantity, price);
        } catch (Exception e) {
            log.warn("GE Star V2: skipping malformed order document - {}", e.getMessage());
            return null;
        }
    }

    /** Pushes an order's current status/fill to its Firestore document. No-op if the order has no linked document (locally-created, not from Firestore). */
    void updateOrderStatus(GeStarOrder order) throws IOException, InterruptedException {
        if (order.getFirestoreDocId() == null) return;

        JsonObject fields = new JsonObject();
        fields.add("status", stringValue(order.getStatus().name()));
        fields.add("quantityFilled", integerValue(order.getQuantityFilled()));
        fields.add("statusDetail", order.getStatusDetail() == null ? nullValue() : stringValue(order.getStatusDetail()));
        fields.add("updatedAt", timestampValueNow());

        JsonObject body = new JsonObject();
        body.add("fields", fields);

        String updateMask = "updateMask.fieldPaths=status&updateMask.fieldPaths=quantityFilled"
            + "&updateMask.fieldPaths=statusDetail&updateMask.fieldPaths=updatedAt";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/" + order.getFirestoreDocId() + "?" + updateMask))
            .header("Authorization", "Bearer " + auth.getAccessToken())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("PATCH order failed: HTTP " + response.statusCode() + " - " + response.body());
        }
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

    private static JsonObject nullValue() {
        JsonObject v = new JsonObject();
        v.add("nullValue", com.google.gson.JsonNull.INSTANCE);
        return v;
    }

    private static JsonObject timestampValueNow() {
        JsonObject v = new JsonObject();
        v.addProperty("timestampValue", java.time.Instant.now().toString());
        return v;
    }
}
