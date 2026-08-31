package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for the local GE Flipper scoring service (data/service/main.py, a FastAPI
 * sidecar you run alongside the client - see that service's README section for setup).
 * Mirrors the OkHttp + Gson pattern used elsewhere in the Hub for talking to local/remote
 * HTTP APIs (see vendor/microbot-hub's ShootingStarApiClient) - both libraries are already
 * on the Microbot client's classpath, no new dependency needed.
 */
@Slf4j
@Singleton
public class ScoringServiceClient {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();

    /**
     * Fetches the top-N ranked flip candidates. Returns an empty list (never null) if the
     * service isn't reachable or returns malformed data - callers should treat that as
     * "nothing to do this scan," not a fatal error, since the service is an optional local
     * sidecar the plugin can't assume is always running.
     */
    public List<Candidate> getCandidates(String baseUrl, int limit) {
        Request request = new Request.Builder()
            .url(baseUrl + "/candidates?limit=" + limit)
            .get()
            .build();

        String body;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("FlipperStar: scoring service returned HTTP {} for /candidates", response.code());
                return Collections.emptyList();
            }
            body = response.body() != null ? response.body().string() : null;
        } catch (Exception e) {
            log.warn("FlipperStar: could not reach scoring service at {} - {}. Is it running? "
                + "(cd data/service && uvicorn main:app --host 127.0.0.1 --port 8420)", baseUrl, e.getMessage());
            return Collections.emptyList();
        }

        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            CandidatesResponse parsed = gson.fromJson(body, CandidatesResponse.class);
            return parsed != null && parsed.getCandidates() != null ? parsed.getCandidates() : Collections.emptyList();
        } catch (JsonSyntaxException e) {
            log.error("FlipperStar: failed to parse scoring service response - {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** True if the scoring service responds to /health. */
    public boolean isHealthy(String baseUrl) {
        Request request = new Request.Builder().url(baseUrl + "/health").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Batched hold/sell decision for every open position, in one call - fetches every
     * position's decision from the exit model at once rather than one HTTP round-trip per
     * item, matching getCandidates' single-bulk-call precedent (see data/service/main.py's
     * POST /should-sell, which itself fetches the live wiki windows once for the whole batch).
     * Returns an empty list (never null) if the service isn't reachable, the exit model isn't
     * loaded yet (HTTP 503), or the response is malformed - callers should treat that as
     * "nothing to do this scan."
     */
    public List<SellDecision> getShouldSellDecisions(String baseUrl, String path, List<OpenPosition> positions) {
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        List<PositionQueryBody> positionBodies = new ArrayList<>();
        for (OpenPosition position : positions) {
            positionBodies.add(new PositionQueryBody(position));
        }
        String jsonBody = gson.toJson(new ShouldSellRequestBody(positionBodies));

        Request request = new Request.Builder()
            .url(baseUrl + path)
            .post(RequestBody.create(MediaType.parse("application/json"), jsonBody))
            .build();

        String body;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("FlipperStar: scoring service returned HTTP {} for {}", response.code(), path);
                return Collections.emptyList();
            }
            body = response.body() != null ? response.body().string() : null;
        } catch (Exception e) {
            log.warn("FlipperStar: could not reach scoring service at {}{} - {}", baseUrl, path, e.getMessage());
            return Collections.emptyList();
        }

        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            ShouldSellResponse parsed = gson.fromJson(body, ShouldSellResponse.class);
            return parsed != null && parsed.getDecisions() != null ? parsed.getDecisions() : Collections.emptyList();
        } catch (JsonSyntaxException e) {
            log.error("FlipperStar: failed to parse should-sell response - {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Request-body shape for POST /should-sell - field names match data/service/main.py's PositionQuery exactly. */
    private static class PositionQueryBody {
        @SerializedName("item_id")
        private final int itemId;
        @SerializedName("quantity_held")
        private final int quantityHeld;
        @SerializedName("average_cost_per_unit")
        private final double averageCostPerUnit;
        @SerializedName("purchase_timestamp")
        private final double purchaseTimestamp;

        PositionQueryBody(OpenPosition position) {
            this.itemId = position.getItemId();
            this.quantityHeld = position.getQuantityHeld();
            this.averageCostPerUnit = position.getAverageCost();
            // GeStarPortfolio tracks acquisition time in epoch millis; the scoring service's
            // PositionQuery.purchase_timestamp is unix seconds (matching Python's time.time()).
            this.purchaseTimestamp = position.getPurchaseTimestampMillis() / 1000.0;
        }
    }

    /** Request-body shape for POST /should-sell - matches data/service/main.py's ShouldSellRequest. */
    private static class ShouldSellRequestBody {
        private final List<PositionQueryBody> positions;

        ShouldSellRequestBody(List<PositionQueryBody> positions) {
            this.positions = positions;
        }
    }
}
