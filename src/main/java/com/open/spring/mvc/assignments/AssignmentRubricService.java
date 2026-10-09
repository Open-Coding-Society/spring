package com.open.spring.mvc.assignments;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generates a thorough, assignment-specific AI grading rubric from the assignment's
 * own page content, instead of every assignment relying on the generic
 * {@link Assignment#DEFAULT_AI_RUBRIC}. Failures always fall back to the caller using
 * that default — rubric generation is a best-effort enhancement, never a hard
 * dependency of assignment creation.
 */
@Service
public class AssignmentRubricService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentRubricService.class);
    private static final String DEFAULT_GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";
    private static final String FALLBACK_GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";
    private static final int MAX_PAGE_CONTENT_CHARS = 24000;
    private static final Duration GEMINI_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TIER_SCORE = Pattern.compile("(?im)^[\\s#*_]*Score\\s+([0-9]+(?:\\.[0-9]+)?)\\b");
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String geminiApiKey;
    private final String geminiApiUrl;

    public AssignmentRubricService(
            ObjectMapper objectMapper,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.url:" + DEFAULT_GEMINI_API_URL + "}") String geminiApiUrl) {
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * @param assignmentName the assignment's name/title
     * @param description    the assignment's short description, if any
     * @param pageContent    the full text of the assignment/lesson page this assignment
     *                       was created from — the primary grounding for the rubric
     * @param maxScore       the assignment's configured maximum score
     * @return a tiered grading rubric, or {@code null} if generation isn't possible
     *         (no API key configured, empty page content, or the AI call failed)
     */
    public String generateRubric(
            String assignmentName,
            String description,
            String pageContent,
            Double maxScore) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            logger.warn("Skipping assignment rubric generation: GEMINI_API_KEY is not configured");
            return null;
        }
        if (maxScore == null || !Double.isFinite(maxScore) || maxScore <= 0) {
            logger.warn("Skipping assignment rubric generation for '{}': invalid maximum score {}",
                    assignmentName, maxScore);
            return null;
        }

        String sourceContent = pageContent == null || pageContent.isBlank()
                ? (description == null || description.isBlank() ? assignmentName : description)
                : pageContent;
        if (sourceContent == null || sourceContent.isBlank()) {
            logger.warn("Skipping assignment rubric generation: assignment has no name, description, or page content");
            return null;
        }

        String prompt = buildPrompt(assignmentName, description, sourceContent, maxScore);

        try {
            JsonNode response = objectMapper.readTree(callGemini(prompt));
            String rubric = response.path("candidates").path(0).path("content").path("parts").path(0)
                    .path("text").asText("").trim();
            if (!isAiRubricReady(rubric, maxScore)) {
                logger.warn("Discarding generated rubric for '{}': its tiers are missing or not scaled to {}",
                        assignmentName, formatScore(aiTopScore(maxScore)));
                return null;
            }
            return rubric;
        } catch (Exception e) {
            logger.warn("Assignment rubric generation failed using {}: {}", geminiApiUrl, e.getMessage());
            return null;
        }
    }

    /** The scale grades are stored on: the assignment's points, or 1 when points aren't set. */
    public static double maxScore(Double points) {
        return points == null || !Double.isFinite(points) || points <= 0 ? 1.0 : points;
    }

    /**
     * The highest score the AI may give: 90% of the points. Full marks are rare and left
     * for the teacher to award by hand.
     */
    public static double aiTopScore(double maxScore) {
        return Math.round(maxScore * 0.9 * 100.0) / 100.0;
    }

    /**
     * Whether {@code rubric} is an assignment-specific AI rubric that grading can trust: not the
     * built-in default, with at least two "Score N" tiers, all at or below {@link #aiTopScore},
     * and the highest one at it. Older rubrics written on a fixed 4-point scale fail this unless
     * the assignment happens to be on that scale, so they get regenerated instead of producing
     * grades on the wrong scale (e.g. 10/100 for a "Score 2" submission).
     */
    public static boolean isAiRubricReady(String rubric, Double points) {
        if (rubric == null || rubric.isBlank() || rubric.strip().equals(Assignment.DEFAULT_AI_RUBRIC.strip())) {
            return false;
        }
        double max = aiTopScore(maxScore(points));
        Matcher matcher = TIER_SCORE.matcher(rubric);
        int tiers = 0;
        double highest = 0;
        while (matcher.find()) {
            double tier = Double.parseDouble(matcher.group(1));
            if (tier < 0 || tier > max) {
                return false;
            }
            highest = Math.max(highest, tier);
            tiers++;
        }
        return tiers >= 2 && max - highest <= Math.max(0.01, max * 0.01);
    }

    private String buildPrompt(String assignmentName, String description, String pageContent, double maxScore) {
        return """
                You are writing a grading rubric for a student assignment. Base the rubric on the ACTUAL assignment content below — be specific to what this assignment asks for, not generic.

                ASSIGNMENT NAME: %s
                ASSIGNMENT DESCRIPTION: %s
                ASSIGNMENT MAXIMUM SCORE: %s

                ASSIGNMENT PAGE CONTENT:
                %s

                Write a thorough, detailed grading rubric with exactly four tiers. Scale every tier to the assignment maximum score above; do not use a fixed 4-point or 5-point scale. Full marks are reserved for the teacher, so the highest tier must be exactly %s (90%% of the maximum), and every score must be numeric and use the same units as the assignment. For example, when the maximum is 1, valid tier scores could be values such as 0.9, 0.8, 0.77, and 0.55. Choose sensible score thresholds for this assignment rather than copying those example values. Mirror this structure and tone, replacing the indicators with specifics grounded in the assignment content:

                Score %s — Strong / Exceptional
                <2-4 sentences describing what a submission that fully and impressively meets THIS assignment's requirements looks like>

                Indicators:
                 <specific indicator 1 for this assignment>
                 <specific indicator 2>
                 <specific indicator 3>
                 <specific indicator 4>

                Score <score below the strong tier> — Adequate
                <2-4 sentences describing a submission that meets the core requirement but lacks depth>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>
                 <specific indicator 3>

                Score <score below the adequate tier> — Limited
                <2-4 sentences describing a partial or shallow submission for THIS assignment>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>
                 <specific indicator 3>

                Score <lowest meaningful score> — Insufficient
                <2-4 sentences describing a submission that fails to address THIS assignment's core requirement>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>

                Return ONLY the rubric text in the structure above — no preamble, no JSON, no markdown code fences.
                """.formatted(
                assignmentName == null ? "" : assignmentName,
                description == null ? "" : description,
                formatScore(maxScore),
                limitPageContent(pageContent),
                formatScore(aiTopScore(maxScore)),
                formatScore(aiTopScore(maxScore)));
    }

    private String limitPageContent(String pageContent) {
        if (pageContent.length() <= MAX_PAGE_CONTENT_CHARS) {
            return pageContent;
        }
        return pageContent.substring(0, MAX_PAGE_CONTENT_CHARS)
                + "\n\n[Remaining page content omitted for rubric generation.]";
    }

    private static String formatScore(double score) {
        return java.math.BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
    }

    private String callGemini(String prompt) throws Exception {
        try {
            return callGeminiEndpoint(geminiApiUrl, prompt);
        } catch (GeminiHttpException exception) {
            if (exception.statusCode() != 404
                    || geminiApiUrl.equals(FALLBACK_GEMINI_API_URL)) {
                throw exception;
            }
            logger.warn("Gemini model endpoint returned 404; retrying rubric generation with {}",
                    FALLBACK_GEMINI_API_URL);
            return callGeminiEndpoint(FALLBACK_GEMINI_API_URL, prompt);
        }
    }

    private String callGeminiEndpoint(String endpoint, String prompt) throws Exception {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(part))),
                "generationConfig", Map.of("temperature", 0.3));
        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        String separator = endpoint.contains("?") ? "&" : "?";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + separator + "key=" + geminiApiKey))
                .timeout(GEMINI_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String providerMessage = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            if (providerMessage.length() > 300) {
                providerMessage = providerMessage.substring(0, 300);
            }
            throw new GeminiHttpException(response.statusCode(),
                    "Gemini returned HTTP " + response.statusCode()
                        + (providerMessage.isBlank() ? "" : ": " + providerMessage));
        }
        return response.body();
    }

    private static final class GeminiHttpException extends Exception {
        private final int statusCode;

        private GeminiHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
