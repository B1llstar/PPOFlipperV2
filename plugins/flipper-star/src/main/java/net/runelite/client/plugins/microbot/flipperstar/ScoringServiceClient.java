package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Singleton;
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
}
