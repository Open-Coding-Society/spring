package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A code assignment may hold a GitHub notebook link when the student's Gist export failed;
 * it must be graded from that link instead of being rejected as "not a Gist".
 */
class AssignmentAiGradingCodeLinkTest {
    private static final String NOTEBOOK = """
            {"cells":[{"cell_type":"code","source":["print('hello')"]}]}""";

    private HttpServer github;
    private AssignmentAiGradingService service;

    @BeforeEach
    void startGithubStub() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/student/work/contents/lesson.ipynb", exchange -> {
            String encoded = Base64.getEncoder().encodeToString(NOTEBOOK.getBytes(StandardCharsets.UTF_8));
            byte[] body = ("{\"content\":\"" + encoded + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.start();
        String baseUrl = "http://127.0.0.1:" + github.getAddress().getPort();
        // No Gemini key: a fetched-and-parsed submission stops at "not configured" instead of calling the AI.
        service = new AssignmentAiGradingService(new ObjectMapper(), mock(FileHandler.class),
                "", "http://unused", baseUrl, "", "");
    }

    @AfterEach
    void stopGithubStub() {
        github.stop(0);
    }

    @Test
    void codeSubmissionWithGithubNotebookLinkIsFetchedForGrading() throws Exception {
        AssignmentAiGradingService.GradeResult result = service.grade(codeSubmission(
                "https://github.com/student/work/blob/main/lesson.ipynb"));

        assertEquals("failed", result.status());
        assertEquals("AI grading is not configured on the server.", result.message());
    }

    @Test
    void codeSubmissionWithUnrelatedLinkIsNotGradeable() throws Exception {
        AssignmentAiGradingService.GradeResult result = service.grade(codeSubmission("https://example.com/notebook.ipynb"));

        assertEquals("not_gradeable", result.status());
    }

    @Test
    void codeSubmissionUrlsAcceptGistsAndGithubFilesOnly() {
        assertTrue(AssignmentAiGradingService.isCodeSubmissionUrl("https://gist.github.com/user/abc123"));
        assertTrue(AssignmentAiGradingService.isCodeSubmissionUrl("https://github.com/o/r/blob/main/dir/a.ipynb"));
        assertFalse(AssignmentAiGradingService.isCodeSubmissionUrl("https://github.com/o/r"));
        assertFalse(AssignmentAiGradingService.isCodeSubmissionUrl("https://example.com/a.ipynb"));
    }

    private AssignmentSubmission codeSubmission(String url) {
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setContent(Map.of("type", "code", "url", url, "fallbackFrom", "gist"));
        return submission;
    }
}
