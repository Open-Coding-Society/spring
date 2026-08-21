package com.open.spring.mvc.person;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;

// Server-to-server call into Flask's /api/internal/sync-password, so a password
// reset completed here (OAuth + student ID verified) also lands on the Flask
// account for the same uid. Gated by a shared secret (INTERNAL_SYNC_KEY) that
// must match Flask's own config -- see GoogleIdTokenVerifier for the same
// env-then-dotenv resolution pattern used here.
public class FlaskPasswordSync {
    private static final Logger logger = LoggerFactory.getLogger(FlaskPasswordSync.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static String resolve(String envKey, String fallback) {
        String value = System.getenv(envKey);
        if (value != null && !value.isBlank()) {
            return value;
        }
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            value = dotenv.get(envKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        } catch (Exception e) {
            // fall through to default
        }
        return fallback;
    }

    // The request body carries the new password in plaintext, so this call is only safe if
    // it's either loopback (same-box, never hits a real network) or TLS-wrapped. Parses the
    // actual host rather than string-prefix-matching flaskUri, since a prefix check like
    // startsWith("http://localhost") would wrongly pass a lookalike host such as
    // "http://localhost.attacker.com".
    private static boolean isSecureTransport(String flaskUri) {
        try {
            URI parsed = URI.create(flaskUri);
            String host = parsed.getHost();
            boolean isLoopback = "localhost".equals(host) || "127.0.0.1".equals(host);
            boolean isHttps = "https".equals(parsed.getScheme());
            return isLoopback || isHttps;
        } catch (Exception e) {
            return false;
        }
    }

    // Best-effort: the Spring-side reset has already succeeded by the time this is
    // called, so a Flask sync failure is logged and swallowed rather than failing
    // the whole request -- the user's new password is already live on Spring,
    // which is the backend this feature actually verified identity against.
    public static boolean syncPassword(String uid, String newPassword) {
        String syncKey = resolve("INTERNAL_SYNC_KEY", null);
        String flaskUri = resolve("FLASK_URI", "http://localhost:8587");

        if (syncKey == null) {
            logger.warn("AUDIT flask_password_sync_skipped uid={} reason=no_sync_key_configured", uid);
            return false;
        }

        if (!isSecureTransport(flaskUri)) {
            logger.warn("AUDIT flask_password_sync_skipped uid={} reason=insecure_flask_uri uri={}", uid, flaskUri);
            return false;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("uid", uid);
            payload.put("password", newPassword);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(flaskUri + "/api/internal/sync-password"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Sync-Key", syncKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                logger.info("AUDIT flask_password_sync_succeeded uid={}", uid);
                return true;
            }

            logger.warn("AUDIT flask_password_sync_failed uid={} status={}", uid, response.statusCode());
            return false;
        } catch (Exception e) {
            logger.warn("AUDIT flask_password_sync_failed uid={} reason=exception msg={}", uid, e.getMessage());
            return false;
        }
    }
}
