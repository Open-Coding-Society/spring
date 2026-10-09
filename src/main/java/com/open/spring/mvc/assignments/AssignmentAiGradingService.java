package com.open.spring.mvc.assignments;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;

@Service
public class AssignmentAiGradingService {
    private static final String DEFAULT_GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";
    // grade() runs synchronously inside the submit request (instant auto-grade) and inside
    // the manual re-grade endpoint. 5 attempts x a 30s per-attempt timeout let a single call
    // block for up to ~3 minutes when Gemini is genuinely down (a real, observed sustained
    // "high demand" 503 outage on Google's side) - the caller's own request timed out long
    // before that ever finished. During a real outage, more attempts don't help: every
    // retry just hits the same overloaded backend. Bounded low so a submission never hangs.
    private static final int MAX_GEMINI_ATTEMPTS = 3;
    private static final Duration GEMINI_REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final long MAX_BACKOFF_MILLIS = 4000L;
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/#?]+)/issues/(\\d+)/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_BLOB_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/#?]+)/blob/([^/#?]+)/(.+?)/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GIST_URL = Pattern.compile(
            "^https?://gist\\.github\\.com/.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GIST_ID = Pattern.compile("[a-fA-F0-9]{6,64}");
    private static final String GIST_MANIFEST_FILE = "ocs.json";
    private static final int MAX_SUBMISSION_CHARS = 60000;
    private static final List<String> TEXT_FILE_EXTENSIONS = List.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".html", ".css", ".md", ".txt",
            ".json", ".c", ".cpp", ".h", ".cs", ".kt", ".rb", ".go", ".rs", ".sql", ".sh",
            ".xml", ".yml", ".yaml", ".csv");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final FileHandler fileHandler;
    private final String geminiApiKey;
    private final String geminiApiUrl;
    private final String githubApiBaseUrl;
    private final String githubApiToken;
    private final String gistToken;

    public AssignmentAiGradingService(
            ObjectMapper objectMapper,
            FileHandler fileHandler,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.url:" + DEFAULT_GEMINI_API_URL + "}") String geminiApiUrl,
            @Value("${github.api.base-url:https://api.github.com}") String githubApiBaseUrl,
            @Value("${github.api.token:}") String githubApiToken,
            @Value("${gist.token:}") String gistToken) {
        this.objectMapper = objectMapper;
        this.fileHandler = fileHandler;
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
        this.githubApiBaseUrl = githubApiBaseUrl;
        this.githubApiToken = githubApiToken;
        this.gistToken = gistToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Validates a GitHub issue URL, e.g. https://github.com/owner/repo/issues/123 */
    public static boolean isGithubIssueUrl(String url) {
        return url != null && GITHUB_ISSUE_URL.matcher(url.trim()).matches();
    }

    /** Validates a GitHub file URL, e.g. https://github.com/owner/repo/blob/main/src/Main.java. */
    public static boolean isGithubBlobUrl(String url) {
        return url != null && GITHUB_BLOB_URL.matcher(url.trim()).matches();
    }

    /** Validates a gist.github.com URL (the shape assets/js/gist.js's exportToGist returns). */
    public static boolean isGistUrl(String url) {
        return url != null && GIST_URL.matcher(url.trim()).matches();
    }

    public GradeResult grade(AssignmentSubmission submission) throws Exception {
        Map<String, Object> content = submission.getContent();
        String contentType = content == null ? null : String.valueOf(content.getOrDefault("type", "")).trim();

        SubmissionText submissionText;
        if ("github_issue".equalsIgnoreCase(contentType) || "link".equalsIgnoreCase(contentType)) {
            submissionText = fetchGithubLinkText(content);
        } else if ("code".equalsIgnoreCase(contentType)) {
            submissionText = fetchGistText(content);
        } else if ("file".equalsIgnoreCase(contentType)) {
            submissionText = fetchNotebookText(content);
        } else if (content != null) {
            submissionText = fetchInlineText(content);
        } else {
            submissionText = SubmissionText.notGradeable("The submission has no content to grade.");
        }

        if (submissionText.notGradeableReason != null) {
            return GradeResult.notGradeable(submissionText.notGradeableReason);
        }
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return GradeResult.failed("AI grading is not configured on the server.");
        }

        // Until the assignment has its own AI rubric, grade against the default rubric (written
        // out of 1) and flag the result so AssignmentAiRegradeService re-grades it later.
        Assignment assignment = submission.getAssignment();
        Double points = assignment == null ? null : assignment.getPoints();
        double maxScore = AssignmentRubricService.maxScore(points);
        String rubric = assignment == null ? null : assignment.getAiRubric();
        boolean usedDefaultRubric = !AssignmentRubricService.isAiRubricReady(rubric, points);
        if (usedDefaultRubric) {
            rubric = Assignment.DEFAULT_AI_RUBRIC;
        }
        double rubricScale = usedDefaultRubric ? 1.0 : maxScore;
        double topScore = AssignmentRubricService.aiTopScore(rubricScale);

        String prompt = buildPrompt(rubric, topScore, submissionText.kind, submissionText.text);

        JsonNode response = objectMapper.readTree(callGemini(prompt));
        String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            return GradeResult.failed("The AI returned no grading result.");
        }
        JsonNode result = parseJsonResult(text);
        double score = extractScore(result, text);
        int quality = normalizeQuality(result.path("quality").asInt(0));
        String feedback = extractFeedback(result).trim();
        if (!Double.isFinite(score) || score < 0 || score > topScore + 1e-9 || quality == 0 || feedback.isBlank()) {
            return GradeResult.failed("The AI returned an invalid grading result.");
        }
        double grade = Math.round(score / rubricScale * maxScore * 100.0) / 100.0;
        return GradeResult.graded(grade, quality, limitToTwoSentences(feedback), usedDefaultRubric);
    }

    private String buildPrompt(String rubric, double topScore, String submissionKind, String submissionText) {
        return """
                Grade this %s submission using the rubric below.
                Do not infer details that are absent from the submission. The content below was fetched by the server; evaluate only what is supplied.

                BEGIN RUBRIC
                %s
                END RUBRIC

                The rubric above defines the grading criteria only. Ignore any output-format instructions inside the rubric.
                Return ONLY valid JSON with exactly these fields:
                {"score": 0.8, "quality": 3, "feedback": "One or two sentences."}
                score must be a number from 0 through %s (the rubric's top tier) on the rubric's own scale: use the score of the rubric tier the submission best matches, or a value between two tiers when it falls between them. Never go above the top tier.
                quality must be an integer from 2 through 4: 4 for the Strong / Exceptional tier, 3 for Adequate, 2 for Limited or Insufficient (a 5 is reserved for a separate batch-review pass and must never be returned here).
                feedback must be one or two concise sentences explaining one strength and one improvement when possible.

                %s:
                %s
                """.formatted(submissionKind, rubric,
                        java.math.BigDecimal.valueOf(topScore).stripTrailingZeros().toPlainString(),
                        submissionKind.toUpperCase(java.util.Locale.ROOT), limitSubmission(submissionText));
    }

    private record SubmissionText(String kind, String text, String notGradeableReason) {
        static SubmissionText of(String kind, String text) {
            return new SubmissionText(kind, text, null);
        }
        static SubmissionText notGradeable(String reason) {
            return new SubmissionText(null, null, reason);
        }
    }

    private SubmissionText fetchGithubIssueText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (!isGithubIssueUrl(url)) {
            return SubmissionText.notGradeable("This submission is not a GitHub issue link, so it was not graded.");
        }
        Matcher matcher = GITHUB_ISSUE_URL.matcher(url);
        matcher.matches();
        String issue = fetchGithubIssue(matcher.group(1), matcher.group(2), matcher.group(3));
        if (issue == null || issue.isBlank()) {
            return SubmissionText.notGradeable("The GitHub issue could not be viewed, so no score was assigned.");
        }
        return SubmissionText.of("GITHUB ISSUE", issue);
    }

    private SubmissionText fetchGithubLinkText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (isGithubIssueUrl(url)) {
            return fetchGithubIssueText(content);
        }
        if (!isGithubBlobUrl(url)) {
            return SubmissionText.notGradeable(
                    "This submission must be a GitHub issue or GitHub file link, so it was not graded.");
        }

        Matcher matcher = GITHUB_BLOB_URL.matcher(url);
        matcher.matches();
        String owner = matcher.group(1);
        String repository = matcher.group(2);
        String reference = matcher.group(3);
        String path = matcher.group(4).replaceFirst("/$", "");
        String fileContent = fetchGithubFile(owner, repository, reference, path);
        if (fileContent == null || fileContent.isBlank()) {
            return SubmissionText.notGradeable("The GitHub file could not be viewed, so no score was assigned.");
        }

        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".ipynb")) {
            try {
                fileContent = extractNotebookText(fileContent);
            } catch (Exception exception) {
                return SubmissionText.notGradeable("The GitHub notebook could not be parsed, so no score was assigned.");
            }
            if (fileContent.isBlank()) {
                return SubmissionText.notGradeable("The GitHub notebook had no gradable content.");
            }
            return SubmissionText.of("JUPYTER NOTEBOOK", fileContent);
        }
        return SubmissionText.of("CODE FILE", fileContent);
    }

    private SubmissionText fetchGistText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (!isGistUrl(url)) {
            return SubmissionText.notGradeable("This submission is not a Gist link, so it was not graded.");
        }
        String gistId = extractGistId(url);
        if (gistId == null) {
            return SubmissionText.notGradeable("Could not determine the Gist id from the submitted link.");
        }
        String files = fetchGistFiles(gistId);
        if (files == null || files.isBlank()) {
            return SubmissionText.notGradeable("The Gist could not be read, so no score was assigned.");
        }
        return SubmissionText.of("CODE SUBMISSION", files);
    }

    private SubmissionText fetchNotebookText(Map<String, Object> content) {
        String filename = String.valueOf(content.getOrDefault("filename", ""));
        String lowerFilename = filename.toLowerCase(java.util.Locale.ROOT);
        boolean notebook = lowerFilename.endsWith(".ipynb");
        if (!notebook && TEXT_FILE_EXTENSIONS.stream().noneMatch(lowerFilename::endsWith)) {
            return SubmissionText.notGradeable("Automatic grading is not available for this file type yet.");
        }
        String uploadedBy = String.valueOf(content.getOrDefault("uploadedBy", ""));
        String storedFilename = String.valueOf(content.getOrDefault("storedFilename", ""));
        if (uploadedBy.isBlank() || storedFilename.isBlank()) {
            return SubmissionText.notGradeable("The notebook file could not be located, so no score was assigned.");
        }
        String base64 = fileHandler.decodeFile(uploadedBy, storedFilename);
        if (base64 == null || base64.isBlank()) {
            return SubmissionText.notGradeable("The notebook file could not be downloaded, so no score was assigned.");
        }
        String notebookJson = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        if (!notebook) {
            if (notebookJson.isBlank()) {
                return SubmissionText.notGradeable("The uploaded file was empty.");
            }
            return SubmissionText.of("CODE FILE (" + filename + ")", notebookJson);
        }
        String extracted;
        try {
            extracted = extractNotebookText(notebookJson);
        } catch (Exception e) {
            return SubmissionText.notGradeable("The notebook file could not be parsed, so no score was assigned.");
        }
        if (extracted.isBlank()) {
            return SubmissionText.notGradeable("The notebook had no gradable content.");
        }
        return SubmissionText.of("JUPYTER NOTEBOOK", extracted);
    }

    private SubmissionText fetchInlineText(Map<String, Object> content) {
        for (String field : List.of("text", "answer", "response", "value", "content", "markdown")) {
            Object value = content.get(field);
            if (value instanceof String text && !text.isBlank()) {
                return SubmissionText.of("TEXT RESPONSE", text.trim());
            }
        }
        return SubmissionText.notGradeable("The submission did not contain readable text.");
    }

    /** Extracts code/markdown cell source text from a .ipynb file's JSON, ignoring outputs. */
    private String extractNotebookText(String notebookJson) throws Exception {
        JsonNode notebook = objectMapper.readTree(notebookJson);
        JsonNode cells = notebook.path("cells");
        StringBuilder out = new StringBuilder();
        for (JsonNode cell : cells) {
            String cellType = cell.path("cell_type").asText("");
            String source = joinSource(cell.path("source"));
            if (source.isBlank()) {
                continue;
            }
            out.append("--- ").append(cellType.isBlank() ? "cell" : cellType).append(" cell ---\n");
            out.append(source).append("\n\n");
        }
        return out.toString().trim();
    }

    private String joinSource(JsonNode source) {
        if (source.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : source) {
                sb.append(line.asText(""));
            }
            return sb.toString();
        }
        return source.asText("");
    }

    private String extractGistId(String urlOrId) {
        Matcher direct = GIST_ID.matcher(urlOrId.trim());
        if (direct.matches()) {
            return urlOrId.trim();
        }
        String[] segments = urlOrId.split("[/#?]");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (GIST_ID.matcher(segments[i]).matches()) {
                return segments[i];
            }
        }
        return null;
    }

    private String fetchGistFiles(String gistId) throws Exception {
        // Public gists are readable without a token; the token only raises the rate limit.
        HttpRequest.Builder request = githubIssueRequest(githubApiBaseUrl.replaceAll("/$", "") + "/gists/" + gistId);
        if (gistToken != null && !gistToken.isBlank()) {
            request.header("Authorization", "Bearer " + gistToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && gistToken != null && !gistToken.isBlank()) {
            response = httpClient.send(githubIssueRequest(githubApiBaseUrl.replaceAll("/$", "") + "/gists/" + gistId).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode gist = objectMapper.readTree(response.body());
        JsonNode files = gist.path("files");
        StringBuilder out = new StringBuilder();
        Iterator<String> fileNames = files.fieldNames();
        while (fileNames.hasNext()) {
            String fileName = fileNames.next();
            if (GIST_MANIFEST_FILE.equals(fileName)) {
                continue;
            }
            String fileContent = files.path(fileName).path("content").asText("");
            out.append("--- ").append(fileName).append(" ---\n").append(fileContent).append("\n\n");
        }
        return out.toString().trim();
    }

    /** Returns the AI's score on the rubric scale, or NaN when none could be found. */
    private double extractScore(JsonNode result, String responseText) {
        for (String field : List.of("score", "grade", "rating", "overall_score", "overallScore")) {
            JsonNode value = result.path(field);
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                Matcher matcher = Pattern.compile("(?i)([0-9]*\\.?[0-9]+)(?:\\s*/\\s*[0-9.]+)?").matcher(value.asText());
                if (matcher.find()) {
                    return Double.parseDouble(matcher.group(1));
                }
            }
        }
        Matcher matcher = Pattern.compile("(?i)\\\"?(?:score|grade|rating|overall[_ ]?score)\\\"?\\s*[:=-]\\s*([0-9]*\\.?[0-9]+)")
                .matcher(responseText);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.NaN;
    }

    private int normalizeQuality(int quality) {
        if (quality <= 0) {
            return 0;
        }
        // Instant auto-grading is limited to 2-4; a 5 is reserved for a separate
        // batch-review pass that picks the single best submission across students.
        return Math.max(2, Math.min(4, quality));
    }

    private String extractFeedback(JsonNode result) {
        for (String field : List.of("feedback", "comments", "comment", "evaluation", "explanation", "reasoning")) {
            JsonNode value = result.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private JsonNode parseJsonResult(String text) throws Exception {
        String normalized = text.replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            normalized = normalized.substring(objectStart, objectEnd + 1);
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("feedback", text.trim());
        }
    }

    private String fetchGithubIssue(String owner, String repository, String number) throws Exception {
        String apiUrl = githubApiBaseUrl.replaceAll("/$", "") + "/repos/" + owner + "/" + repository + "/issues/" + number;
        HttpRequest.Builder request = githubIssueRequest(apiUrl);
        if (githubApiToken != null && !githubApiToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubApiToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && githubApiToken != null && !githubApiToken.isBlank()) {
            response = httpClient.send(githubIssueRequest(apiUrl).build(), HttpResponse.BodyHandlers.ofString());
        }
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

    private String fetchGithubFile(String owner, String repository, String reference, String path) throws Exception {
        String apiUrl = githubApiBaseUrl.replaceAll("/$", "") + "/repos/" + owner + "/" + repository
                + "/contents/" + path + "?ref=" + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8);
        HttpRequest.Builder request = githubIssueRequest(apiUrl);
        if (githubApiToken != null && !githubApiToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubApiToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && githubApiToken != null && !githubApiToken.isBlank()) {
            response = httpClient.send(githubIssueRequest(apiUrl).build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode file = objectMapper.readTree(response.body());
        String encodedContent = file.path("content").asText("").replaceAll("\\s", "");
        if (encodedContent.isBlank()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder githubIssueRequest(String apiUrl) {
        return HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "OpenCodingSociety-assignment-grader")
                .GET();
    }

    private String callGemini(String prompt) throws Exception {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of("parts", List.of(part))),
            "generationConfig", Map.of(
                "responseMimeType", "application/json",
                "temperature", 0.2));
        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        for (int attempt = 0; attempt < MAX_GEMINI_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(geminiApiUrl + "?key=" + geminiApiKey))
                    .timeout(GEMINI_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException timeout) {
                // Slow responses are as transient as a 503: retry rather than leave the submission ungraded.
                if (attempt == MAX_GEMINI_ATTEMPTS - 1) {
                    throw timeout;
                }
                continue;
            }
            if (response.statusCode() == 200) {
                return response.body();
            }
            if (!isRetryableGeminiStatus(response.statusCode()) || attempt == MAX_GEMINI_ATTEMPTS - 1) {
                throw new IllegalStateException("Gemini returned HTTP " + response.statusCode());
            }
            waitBeforeRetry(attempt, response);
        }
        throw new IllegalStateException("Gemini request was not completed");
    }

    private void waitBeforeRetry(int attempt, HttpResponse<String> response) throws InterruptedException {
        long retryAfter = response.headers().firstValue("Retry-After")
                .map(this::parseRetryAfterMillis)
                .orElse(0L);
        long exponentialDelay = Math.min(MAX_BACKOFF_MILLIS, 1000L << attempt);
        Thread.sleep(retryAfter > 0 ? Math.min(retryAfter, MAX_BACKOFF_MILLIS) : exponentialDelay);
    }

    private long parseRetryAfterMillis(String value) {
        try {
            return Long.parseLong(value.trim()) * 1000L;
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private boolean isRetryableGeminiStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private String limitSubmission(String text) {
        if (text.length() <= MAX_SUBMISSION_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SUBMISSION_CHARS) + "\n\n[Remaining submission content omitted.]";
    }

    private String limitToTwoSentences(String feedback) {
        String[] sentences = feedback.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) {
            return feedback;
        }
        return sentences[0] + " " + sentences[1];
    }

    /**
     * @param grade             score on the assignment's points scale (out of 1 when points aren't set)
     * @param qualityScore      2-4 quality tier shown as "AI quality" in the tracker
     * @param usedDefaultRubric true when the assignment had no usable AI rubric yet, so the
     *                          submission should be re-graded once one is generated
     */
    public record GradeResult(String status, Double grade, Integer qualityScore, String feedback, String message,
            boolean usedDefaultRubric) {
        static GradeResult graded(double grade, int qualityScore, String feedback, boolean usedDefaultRubric) {
            return new GradeResult("graded", grade, qualityScore, feedback, null, usedDefaultRubric);
        }
        static GradeResult notGradeable(String message) { return new GradeResult("not_gradeable", null, null, null, message, false); }
        static GradeResult failed(String message) { return new GradeResult("failed", null, null, null, message, false); }

        /** Copies a graded result onto the submission, flagging it for re-grade if the default rubric was used. */
        public void applyTo(AssignmentSubmission submission) {
            submission.setGrade(grade);
            submission.setQualityScore(qualityScore);
            submission.setFeedback(feedback);
            submission.setAiSummary(feedback);
            submission.setNeedsAiRegrade(usedDefaultRubric);
        }
    }
}
