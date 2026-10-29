package com.open.spring.mvc.toolchain;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The game's Station Helper: a signed-in student asks for help at any station
 * and Gemini answers using their progress. Nothing is saved; the page keeps the
 * conversation.
 */
@RestController
@RequestMapping("/api/cs-pathway/chat")
public class ToolchainChatApiRestController {
    /**
     * DTO for a student's question to the Station Helper.
     *
     * @param levelKey the level the student is playing, e.g. "toolchain-trail"
     * @param question what the student typed
     * @param progress where the student is in the level, so the answer fits their station
     * @param history the last few turns of this chat, oldest first
     */
    public record ChatRequestRecord(String levelKey, String question, ProgressRecord progress, List<TurnRecord> history) {
    }

    /**
     * DTO for the student's progress as the game sees it.
     *
     * @param stationName the station the student is working on, or null when the level is finished
     * @param stationSkill the real-world skill that station teaches
     * @param stationStatus "active", "stuck" (no progress for a while) or "failed" (a check failed)
     * @param os the operating system the student picked, e.g. "macOS"
     * @param completedStations names of the stations already verified
     * @param totalStations how many stations the level has
     * @param stationSteps the current station's instructions, as shown in the game
     * @param failedChecks the checks that failed on the student's last try, if any
     */
    public record ProgressRecord(
            String stationName,
            String stationSkill,
            String stationStatus,
            String os,
            List<String> completedStations,
            Integer totalStations,
            List<String> stationSteps,
            List<String> failedChecks) {
    }

    /** DTO for one chat message: role is "student" or "helper". */
    public record TurnRecord(String role, String text) {
    }

    private final ToolchainChatService chatService;

    public ToolchainChatApiRestController(ToolchainChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Answers a student's question.
     *
     * The student identity comes from the authenticated principal. The service
     * validates the question and enforces the short per-student cooldown.
     *
     * POST /api/cs-pathway/chat
     * Body: {"levelKey": "toolchain-trail", "question": "...", "progress": {...}, "history": [...]}
     *
     * @param user the signed-in student
     * @param body the question, progress and recent turns
     * @return 200 with {"answer": ...}, 401 when signed out, 400 for an invalid question,
     *         429 during the cooldown, or 503 when Gemini cannot answer
     */
    @PostMapping
    public ResponseEntity<?> ask(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody(required = false) ChatRequestRecord body) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to use the chat helper."));
        }
        try {
            return ResponseEntity.ok(Map.of("answer", chatService.ask(user.getUsername(), body)));
        } catch (ToolchainChatExceptionTooSoon error) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(error.getRetryAfterSeconds()))
                    .body(Map.of("error", error.getMessage(), "retryAfterSeconds", error.getRetryAfterSeconds()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", error.getMessage()));
        }
    }
}
