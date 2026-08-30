package net.runelite.client.plugins.microbot.gestarv2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints short-lived Google OAuth2 access tokens from a service-account JSON key, using the
 * standard JWT bearer flow (RFC 7523) signed with plain java.security RSA - no Google Auth
 * library dependency, matching how {@code Rs2GrandExchange} already talks to external HTTPS
 * APIs in this codebase without pulling in an SDK.
 *
 * The service-account key never leaves this class or the outgoing token request to Google;
 * everything else in the plugin only ever sees the resulting short-lived access token.
 */
@Slf4j
class GoogleServiceAccountAuth {

    private static final String FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;
    final String projectId;

    private volatile String cachedAccessToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    GoogleServiceAccountAuth(Path serviceAccountJsonPath) throws IOException {
        String json = new String(Files.readAllBytes(serviceAccountJsonPath), StandardCharsets.UTF_8);
        JsonObject obj = new JsonParser().parse(json).getAsJsonObject();

        this.clientEmail = obj.get("client_email").getAsString();
        this.tokenUri = obj.get("token_uri").getAsString();
        this.projectId = obj.get("project_id").getAsString();
        this.privateKey = parsePrivateKey(obj.get("private_key").getAsString());
    }

    private static PrivateKey parsePrivateKey(String pem) {
        String base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse service account private key", e);
        }
    }

    /** Returns a cached access token if it still has at least a minute of life left, otherwise mints a new one. */
    synchronized String getAccessToken() throws IOException, InterruptedException {
        if (cachedAccessToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(60))) {
            return cachedAccessToken;
        }

        String jwt = signAssertionJwt();
        String body = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
            + "&assertion=" + urlEncode(jwt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token exchange failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
        cachedAccessToken = json.get("access_token").getAsString();
        int expiresInSeconds = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
        cachedTokenExpiry = Instant.now().plusSeconds(expiresInSeconds);
        return cachedAccessToken;
    }

    private String signAssertionJwt() {
        long nowSeconds = Instant.now().getEpochSecond();

        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");

        JsonObject claims = new JsonObject();
        claims.addProperty("iss", clientEmail);
        claims.addProperty("scope", FIRESTORE_SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", nowSeconds);
        claims.addProperty("exp", nowSeconds + 3600);

        String unsigned = base64UrlEncode(header.toString()) + "." + base64UrlEncode(claims.toString());

        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
            byte[] signed = signature.sign();
            return unsigned + "." + URL_ENCODER.encodeToString(signed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign service account JWT", e);
        }
    }

    private static String base64UrlEncode(String s) {
        return URL_ENCODER.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
