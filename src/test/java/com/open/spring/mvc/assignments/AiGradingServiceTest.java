package com.open.spring.mvc.assignments;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the two parts of the AI check that run on whatever the model and the
 * student happen to send: reading the reply, and deciding what the model is
 * shown. The Gemini call itself is not exercised here.
 */
public class AiGradingServiceTest {

    private final AiGradingService service = new AiGradingService();

    private static String geminiReply(String modelText) {
        String escaped = modelText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}";
    }

    @Test
    public void readsSummaryAndScore() throws Exception {
        String reply = geminiReply(
                "[{\"id\": 7, \"summary\": \"Loop bound is off by one.\", \"quality_score\": 3}]");

        Map<Long, AiGradingService.AiVerdict> out = service.parseVerdicts(reply);

        assertEquals(1, out.size(), "one submission in, one verdict out");
        assertEquals("Loop bound is off by one.", out.get(7L).summary);
        assertEquals(3, out.get(7L).qualityScore);
    }

    @Test
    public void readsJsonWrappedInACodeFence() throws Exception {
        // Models routinely wrap JSON in ```json even when told not to.
        String reply = geminiReply(
                "```json\n[{\"id\": 2, \"summary\": \"Complete and correct.\", \"quality_score\": 5}]\n```");

        Map<Long, AiGradingService.AiVerdict> out = service.parseVerdicts(reply);

        assertEquals(1, out.size(), "a fenced reply should still be read");
        assertEquals(5, out.get(2L).qualityScore);
    }

    @Test
    public void dropsAScoreOutsideTheScale() throws Exception {
        // The column only accepts 1-5. A model that answers 9 should leave the
        // score empty rather than be clamped into looking confident.
        String reply = geminiReply(
                "[{\"id\": 4, \"summary\": \"Nothing to read at that link.\", \"quality_score\": 9}]");

        Map<Long, AiGradingService.AiVerdict> out = service.parseVerdicts(reply);

        assertNotNull(out.get(4L), "the summary is still usable");
        assertNull(out.get(4L).qualityScore, "an out-of-range score is dropped, not clamped");
    }

    @Test
    public void survivesGarbageWithoutThrowing() throws Exception {
        assertTrue(service.parseVerdicts("not json at all").isEmpty());
        assertTrue(service.parseVerdicts("").isEmpty());
        assertTrue(service.parseVerdicts(null).isEmpty());
        assertTrue(service.parseVerdicts(geminiReply("I could not do that.")).isEmpty(),
                "prose with no JSON array yields nothing to save");
    }

    @Test
    public void skipsAnEntryWithAnUnreadableId() throws Exception {
        String reply = geminiReply(
                "[{\"id\": \"abc\", \"summary\": \"x\", \"quality_score\": 2},"
              + " {\"id\": 5, \"summary\": \"Good trace.\", \"quality_score\": 4}]");

        Map<Long, AiGradingService.AiVerdict> out = service.parseVerdicts(reply);

        assertEquals(1, out.size(), "the unreadable entry is skipped, the good one is kept");
        assertEquals("Good trace.", out.get(5L).summary);
    }

    @Test
    public void describesALinkSubmissionWithItsTopic() {
        AssignmentSubmission s = new AssignmentSubmission();
        Map<String, Object> content = new HashMap<>();
        content.put("type", "link");
        content.put("url", "https://github.com/example/repo");
        content.put("topicTitle", "Nested Loops");
        content.put("notes", "second attempt");
        s.setContent(content);

        String described = service.describeSubmission(s);

        assertTrue(described.contains("Nested Loops"), "the topic gives the model context");
        assertTrue(described.contains("https://github.com/example/repo"));
        assertTrue(described.contains("second attempt"));
    }

    @Test
    public void describesAnEmptySubmissionPlainly() {
        AssignmentSubmission s = new AssignmentSubmission();
        assertEquals("(nothing recorded)", service.describeSubmission(s));

        s.setContent(new HashMap<>());
        assertEquals("(nothing recorded)", service.describeSubmission(s));
    }

    @Test
    public void truncatesVeryLongSubmissions() {
        AssignmentSubmission s = new AssignmentSubmission();
        Map<String, Object> content = new HashMap<>();
        content.put("notes", "x".repeat(9000));
        s.setContent(content);

        String described = service.describeSubmission(s);

        assertTrue(described.length() < 9000, "a huge submission is cut before it is sent");
        assertTrue(described.endsWith("... (truncated)"), "and it says so");
    }

    @Test
    public void picksUpOnlyTheConfiguredCourse() throws Exception {
        // Default is csa. A CSH submission must be left alone entirely -- that
        // course never asked for an automatic AI check.
        java.lang.reflect.Field f = AiGradingService.class.getDeclaredField("courseFilter");
        f.setAccessible(true);
        f.set(service, "csa");

        AssignmentSubmission csa = new AssignmentSubmission();
        Map<String, Object> csaContent = new HashMap<>();
        csaContent.put("course", "csa");
        csa.setContent(csaContent);

        AssignmentSubmission csh = new AssignmentSubmission();
        Map<String, Object> cshContent = new HashMap<>();
        cshContent.put("course", "csh");
        csh.setContent(cshContent);

        AssignmentSubmission noCourse = new AssignmentSubmission();
        noCourse.setContent(new HashMap<>());

        assertTrue(service.inConfiguredCourse(csa), "CSA work is checked");
        assertFalse(service.inConfiguredCourse(csh), "CSH work is not touched");
        assertFalse(service.inConfiguredCourse(noCourse), "work with no course recorded is left for a teacher");
    }

    @Test
    public void anEmptyCourseFilterChecksEverything() throws Exception {
        java.lang.reflect.Field f = AiGradingService.class.getDeclaredField("courseFilter");
        f.setAccessible(true);
        f.set(service, "");

        AssignmentSubmission csh = new AssignmentSubmission();
        Map<String, Object> content = new HashMap<>();
        content.put("course", "csh");
        csh.setContent(content);

        assertTrue(service.inConfiguredCourse(csh), "an empty filter opts every course in");
    }
}
