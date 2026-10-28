package com.open.spring.mvc.toolchain;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends one generateContent request to Gemini and returns the answer text.
 * Uses the same gemini.api.key / gemini.api.url settings as the setup report review.
 */
@Component
public class ToolchainGeminiClient {
    // A student is waiting in the game, so give up sooner than the teacher-side review does.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    // 429/503 and timeouts are usually short demand spikes, so a couple of retries often succeed.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final Logger logger = LoggerFactory.getLogger(ToolchainGeminiClient.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiUrl;
    private final HttpClient httpClient;

    public ToolchainGeminiClient(
            ObjectMapper objectMapper,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent}") String apiUrl) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param requestBody a Gemini generateContent body (contents, systemInstruction, generationConfig)
     * @return the answer text, never blank
     * @throws IllegalStateException with a message safe to show, when Gemini cannot answer
     */
    public String generate(Map<String, Object> requestBody) {
        if (!isConfigured()) {
            throw new IllegalStateException("The AI helper is not configured on the server.");
        }
        try {
            String text = readAnswerText(objectMapper.readTree(sendWithRetries(requestBody)));
            if (text.isBlank()) {
                throw new IllegalStateException("The AI returned an empty answer. Try asking again.");
            }
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The AI request was interrupted.", error);
        } catch (HttpTimeoutException error) {
            logger.warn("Gemini timed out after {} attempts", MAX_ATTEMPTS);
            throw new IllegalStateException("The AI took too long to answer. Try again in a minute.", error);
        } catch (JsonProcessingException error) {
            logger.warn("Gemini returned unreadable JSON: {}", error.getOriginalMessage());
            throw new IllegalStateException("The AI returned an answer the server could not read.", error);
        } catch (IOException error) {
            logger.warn("Could not reach Gemini: {}", error.toString());
            throw new IllegalStateException("Could not reach the AI service. Try again in a minute.", error);
        }
    }

    /** The first non-thought text part; thinking models may put their reasoning in earlier parts. */
    private static String readAnswerText(JsonNode response) {
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.hasNonNull("text")) {
                return part.path("text").asText("").trim();
            }
        }
        return "";
    }

    private String sendWithRetries(Map<String, Object> requestBody) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(requestBody);
        for (int attempt = 1; ; attempt++) {
            boolean lastAttempt = attempt == MAX_ATTEMPTS;
            try {
                HttpResponse<String> response = send(json);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (lastAttempt || !isTemporaryFailure(status)) {
                    logger.warn("Gemini returned HTTP {} on attempt {}", status, attempt);
                    throw new IllegalStateException(describeHttpFailure(status));
                }
            } catch (HttpTimeoutException error) {
                if (lastAttempt) {
                    throw error;
                }
            }
            Thread.sleep(RETRY_DELAY.toMillis());
        }
    }

    private static boolean isTemporaryFailure(int status) {
        return status == 429 || status == 500 || status == 503;
    }

    /** Messages a student may see, so they say what to do instead of naming config keys. */
    private static String describeHttpFailure(int status) {
        return switch (status) {
            case 429, 503 -> "The AI helper is busy right now. Try again in a minute.";
            default -> "The AI helper is not working right now (Gemini returned HTTP " + status
                    + "). Use the red flag to ask your teacher.";
        };
    }

    private HttpResponse<String> send(String json) throws IOException, InterruptedException {
        String querySeparator = apiUrl.contains("?") ? "&" : "?";
        URI uri = URI.create(apiUrl + querySeparator + "key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
