package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runs the AI check over submissions nobody has checked yet.
 *
 * Grading used to need a person: a teacher opened the submissions dashboard and
 * pressed "Run AI check", which sent a batch to the Flask summarizer. Work
 * submitted after that press sat unchecked until the next one.
 *
 * Doing it here instead means the server grades on its own authority, so the
 * endpoint that writes the result stays teacher-only and a student never needs
 * -- and never gets -- permission to grade anything.
 *
 * What it will not do:
 *   - touch `grade`. That is the teacher's number. This writes `aiSummary` and
 *     `qualityScore`, which the dashboard shows as the AI check.
 *   - re-check a submission. It only picks up rows where `aiSummary` is null, so
 *     a second pass cannot overwrite the first, or a teacher's own edit.
 *   - stop anyone submitting. Every failure here is caught and logged; a Gemini
 *     outage leaves work unchecked, never unsubmitted.
 *
 * With no GEMINI_API_KEY set the service logs once and stays asleep, so a deploy
 * without a key behaves exactly as it did before.
 */
@Service
@EnableScheduling
public class AiGradingService {

    /** How often to look for unchecked work. */
    private static final long SWEEP_INTERVAL_MS = 300_000; // 5 minutes

    /** Most submissions to send in one sweep, to bound cost and stay under rate limits. */
    private static final int MAX_PER_SWEEP = 20;

    /** Submission text longer than this is truncated before it is sent. */
    private static final int MAX_TEXT_CHARS = 4000;

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;

    /**
     * Any OpenAI-shaped chat completions endpoint. Groq, OpenAI, Together,
     * Cerebras and OpenRouter all speak this, and so does Gemini through
     * https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
     * -- so the provider is a config line rather than a code change.
     */
    @Value("${ai.grading.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    /** Falls back to GEMINI_API_KEY so an existing deploy keeps working. */
    @Value("${ai.grading.key:${GROQ_API_KEY:${GEMINI_API_KEY:}}}")
    private String apiKey;

    @Value("${ai.grading.model:llama-3.3-70b-versatile}")
    private String model;

    /** Set false to leave submissions for the dashboard button instead. */
    @Value("${ai.grading.enabled:true}")
    private boolean enabled;

    /**
     * Which course's work to check, matched against `content.course`.
     *
     * Only the CSA lesson form records a course today, so the default keeps this
     * to CSA and leaves every other course exactly as it is. Set it empty to
     * check everything.
     */
    @Value("${ai.grading.course:csa}")
    private String courseFilter;

    private final ObjectMapper mapper = new ObjectMapper();
    private boolean warnedAboutMissingKey = false;

    @Scheduled(fixedRate = SWEEP_INTERVAL_MS)
    @Transactional
    public void sweepUncheckedSubmissions() {
        if (!enabled) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            if (!warnedAboutMissingKey) {
                System.out.println("[ai-grading] No ai.grading.key (or GROQ_API_KEY/GEMINI_API_KEY) set; "
                        + "the AI check will not run automatically.");
                warnedAboutMissingKey = true;
            }
            return;
        }

        List<AssignmentSubmission> pending = findUnchecked();
        if (pending.isEmpty()) {
            return;
        }

        System.out.println("[ai-grading] Checking " + pending.size() + " submission(s).");
        Map<Long, AiVerdict> verdicts;
        try {
            verdicts = askModel(pending);
        } catch (Exception e) {
            // A grader that is down is not a reason to disturb anything else.
            System.out.println("[ai-grading] Model call failed, leaving these for the next sweep: " + e.getMessage());
            return;
        }

        int saved = 0;
        for (AssignmentSubmission submission : pending) {
            AiVerdict verdict = verdicts.get(submission.getId());
            if (verdict == null || verdict.summary == null || verdict.summary.isBlank()) {
                continue;
            }
            // Re-read the flag rather than trusting the copy we started with: a
            // teacher may have pressed the dashboard button while this ran.
            if (submission.getAiSummary() != null) {
                continue;
            }
            submission.setAiSummary(verdict.summary);
            if (verdict.qualityScore != null) {
                submission.setQualityScore(verdict.qualityScore);
            }
            submissionRepo.save(submission);
            saved++;
        }
        System.out.println("[ai-grading] Wrote " + saved + " AI check(s).");
    }

    /** Submissions with no AI summary yet, in the configured course, capped for one sweep. */
    private List<AssignmentSubmission> findUnchecked() {
        List<AssignmentSubmission> out = new ArrayList<>();
        for (AssignmentSubmission s : submissionRepo.findAll()) {
            if (s.getAiSummary() != null && !s.getAiSummary().isBlank()) {
                continue;
            }
            if (!inConfiguredCourse(s)) {
                continue;
            }
            out.add(s);
            if (out.size() >= MAX_PER_SWEEP) {
                break;
            }
        }
        return out;
    }

    /**
     * Whether this submission belongs to the course being checked.
     *
     * Work submitted through a lesson form carries `course` in its content.
     * Anything older has no course recorded, so it is left alone rather than
     * guessed at -- a teacher can still check it with the dashboard button.
     */
    boolean inConfiguredCourse(AssignmentSubmission s) {
        if (courseFilter == null || courseFilter.isBlank()) {
            return true;
        }
        Map<String, Object> content = s.getContent();
        if (content == null) {
            return false;
        }
        Object course = content.get("course");
        return course != null && courseFilter.equalsIgnoreCase(course.toString().trim());
    }

    /**
     * One call for the whole batch, answered as JSON so the reply can be
     * matched back to each submission by id.
     */
    private Map<Long, AiVerdict> askModel(List<AssignmentSubmission> batch) throws Exception {
        StringBuilder work = new StringBuilder();
        for (AssignmentSubmission s : batch) {
            work.append("---\n")
                .append("id: ").append(s.getId()).append('\n')
                .append("assignment: ").append(assignmentNameOf(s)).append('\n')
                .append("submitted: ").append(describeSubmission(s)).append('\n');
        }

        String prompt = """
                You are checking student work for an AP Computer Science A class.

                For each submission below, write one or two plain sentences a student can act on,
                and give a quality score from 1 to 5, where 1 is almost nothing to go on and 5 is
                complete and correct. Judge only what you can actually see. If a submission is a
                bare link with no content you can read, say so plainly and score it 1.

                You are writing a first draft for the teacher, not the grade of record. Do not
                invent a percentage or a letter grade.

                Reply with a JSON object holding one key, "results", whose value is an array:
                {"results": [{"id": <submission id>, "summary": "<your sentences>", "quality_score": <1-5>}]}

                Submissions:
                %s
                """.formatted(work.toString());

        // Built with Jackson so a quote or newline in student work cannot break
        // out of the JSON string.
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        ArrayNode messages = mapper.createArrayNode().add(message);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.set("messages", messages);
        // Ask for JSON directly rather than parsing it back out of prose.
        payload.set("response_format", mapper.createObjectNode().put("type", "json_object"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(payload), headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, request, String.class);

        return parseVerdicts(response.getBody());
    }

    /**
     * Reads the verdicts out of an OpenAI-shaped reply.
     *
     * Anything unreadable -- a truncated reply, an HTML error page from a proxy,
     * prose where JSON was asked for -- yields no verdicts rather than an
     * exception, so one bad reply costs a sweep and nothing more.
     */
    Map<Long, AiVerdict> parseVerdicts(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyMap();
        }
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            return Collections.emptyMap();
        }

        // JSON mode returns a bare object, but a model ignoring it may still
        // fence the reply, so find the JSON rather than assume it starts at 0.
        String raw = content.asText();
        JsonNode items = readResults(raw);
        if (items == null || !items.isArray()) {
            return Collections.emptyMap();
        }

        Map<Long, AiVerdict> out = new java.util.HashMap<>();
        for (JsonNode item : items) {
            if (!item.hasNonNull("id")) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(item.get("id").asText().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            Integer score = null;
            if (item.hasNonNull("quality_score")) {
                int parsed = item.get("quality_score").asInt(0);
                // Anything outside the scale the column accepts is dropped rather
                // than clamped, so a confused model does not look confident.
                score = (parsed >= 1 && parsed <= 5) ? parsed : null;
            }
            out.put(id, new AiVerdict(item.path("summary").asText(""), score));
        }
        return out;
    }

    /** The results array, whether the model returned {"results": [...]} or a bare [...]. */
    private JsonNode readResults(String raw) {
        int obj = raw.indexOf('{');
        int arr = raw.indexOf('[');
        try {
            if (obj >= 0 && (arr < 0 || obj < arr)) {
                JsonNode parsed = mapper.readTree(raw.substring(obj, raw.lastIndexOf('}') + 1));
                return parsed.path("results");
            }
            if (arr >= 0) {
                return mapper.readTree(raw.substring(arr, raw.lastIndexOf(']') + 1));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String assignmentNameOf(AssignmentSubmission s) {
        return s.getAssignment() != null && s.getAssignment().getName() != null
                ? s.getAssignment().getName()
                : "Unknown assignment";
    }

    /**
     * What the model gets to look at. `content` is a free-form map, so this reads
     * the keys the submit forms actually write and ignores the rest.
     */
    String describeSubmission(AssignmentSubmission s) {
        Map<String, Object> content = s.getContent();
        if (content == null || content.isEmpty()) {
            return "(nothing recorded)";
        }
        StringBuilder sb = new StringBuilder();
        Object type = content.get("type");
        Object url = content.get("url");
        Object filename = content.get("filename");
        Object notes = content.get("notes");
        Object topicTitle = content.get("topicTitle");

        if (topicTitle != null) {
            sb.append("topic: ").append(topicTitle).append('\n');
        }
        if (type != null) {
            sb.append("type: ").append(type).append('\n');
        }
        if (url != null) {
            sb.append("url: ").append(url).append('\n');
        }
        if (filename != null) {
            sb.append("file: ").append(filename).append('\n');
        }
        if (notes != null && !notes.toString().isBlank()) {
            sb.append("notes: ").append(notes).append('\n');
        }
        if (s.getComment() != null && !s.getComment().isBlank()) {
            sb.append("student comment: ").append(s.getComment()).append('\n');
        }

        String out = sb.length() == 0 ? content.toString() : sb.toString();
        return out.length() > MAX_TEXT_CHARS ? out.substring(0, MAX_TEXT_CHARS) + "... (truncated)" : out;
    }

    /** One submission's AI check. */
    static final class AiVerdict {
        final String summary;
        final Integer qualityScore;

        AiVerdict(String summary, Integer qualityScore) {
            this.summary = summary;
            this.qualityScore = qualityScore;
        }
    }
}
