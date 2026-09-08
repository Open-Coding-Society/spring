package com.open.spring.mvc.assignments;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssignmentAiGradingService {
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/#?]+)/issues/(\\d+)/?$",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String geminiApiKey;
    private final String geminiApiUrl;
    private final String githubApiBaseUrl;
    private final String githubApiToken;

    public AssignmentAiGradingService(
            ObjectMapper objectMapper,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent}") String geminiApiUrl,
            @Value("${github.api.base-url:https://api.github.com}") String githubApiBaseUrl,
            @Value("${github.api.token:}") String githubApiToken) {
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
        this.githubApiBaseUrl = githubApiBaseUrl;
        this.githubApiToken = githubApiToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public GradeResult grade(AssignmentSubmission submission) throws Exception {
        Map<String, Object> content = submission.getContent();
        String url = content == null ? null : String.valueOf(content.getOrDefault("url", "")).trim();
        Matcher matcher = url == null ? null : GITHUB_ISSUE_URL.matcher(url);
        if (matcher == null || !matcher.matches()) {
            return GradeResult.notGradeable("This submission is not a GitHub issue link, so it was not graded.");
        }
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return GradeResult.failed("AI grading is not configured on the server.");
        }

        String issue = fetchGithubIssue(matcher.group(1), matcher.group(2), matcher.group(3));
        if (issue == null || issue.isBlank()) {
            return GradeResult.notGradeable("The GitHub issue could not be viewed, so no score was assigned.");
        }

        String rubric = submission.getAssignment() == null
                ? Assignment.DEFAULT_AI_RUBRIC
                : submission.getAssignment().getAiRubric();
        if (rubric == null || rubric.isBlank()) {
            rubric = Assignment.DEFAULT_AI_RUBRIC;
        }

        String prompt = """
                Grade this GitHub issue submission using the rubric below.
                Return ONLY valid JSON with exactly these fields:
                {"score": 1, "feedback": "One or two sentences."}
                score must be an integer from 1 through 5. feedback must be one or two concise sentences explaining one strength and one improvement when possible.
                Do not infer details that are absent from the issue. The issue content was fetched by the server; evaluate only the supplied content.

                RUBRIC:
                %s

                GITHUB ISSUE:
                %s
                """.formatted(rubric, issue);

        JsonNode response = objectMapper.readTree(callGemini(prompt));
        String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            return GradeResult.failed("The AI returned no grading result.");
        }
        text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        JsonNode result = objectMapper.readTree(text);
        int score = result.path("score").asInt(0);
        String feedback = result.path("feedback").asText("").trim();
        if (score < 1 || score > 5 || feedback.isBlank()) {
            return GradeResult.failed("The AI returned an invalid grading result.");
        }
        return GradeResult.graded(score, limitToTwoSentences(feedback));
    }

    private String fetchGithubIssue(String owner, String repository, String number) throws Exception {
        String apiUrl = githubApiBaseUrl.replaceAll("/$", "") + "/repos/" + owner + "/" + repository + "/issues/" + number;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenCodingSociety-assignment-grader")
                .GET();
        if (githubApiToken != null && !githubApiToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubApiToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode issue = objectMapper.readTree(response.body());
        if (issue.has("pull_request")) {
            return null;
        }
        return "Title: " + issue.path("title").asText("")
                + "\nAuthor: " + issue.path("user").path("login").asText("")
                + "\nState: " + issue.path("state").asText("")
                + "\nBody:\n" + issue.path("body").asText("")
                + "\nLabels: " + issue.path("labels").toString();
    }

    private String callGemini(String prompt) throws Exception {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> requestBody = Map.of("contents", List.of(Map.of("parts", List.of(part))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(geminiApiUrl + "?key=" + geminiApiKey))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gemini returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String limitToTwoSentences(String feedback) {
        String[] sentences = feedback.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) {
            return feedback;
        }
        return sentences[0] + " " + sentences[1];
    }

    public record GradeResult(String status, Integer score, String feedback, String message) {
        static GradeResult graded(int score, String feedback) { return new GradeResult("graded", score, feedback, null); }
        static GradeResult notGradeable(String message) { return new GradeResult("not_gradeable", null, null, message); }
        static GradeResult failed(String message) { return new GradeResult("failed", null, null, message); }
    }
}
