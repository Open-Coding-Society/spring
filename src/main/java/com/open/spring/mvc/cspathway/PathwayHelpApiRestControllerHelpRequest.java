package com.open.spring.mvc.cspathway;

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
 * The game's red flag button: a student asks their teacher for help.
 * Teachers read the requests on /mvc/cs-pathway/read.
 */
@RestController
@RequestMapping("/api/cs-pathway/help-requests")
public class PathwayHelpApiRestControllerHelpRequest {
    private final PathwayHelpService helpRequestService;

    public PathwayHelpApiRestControllerHelpRequest(PathwayHelpService helpRequestService) {
        this.helpRequestService = helpRequestService;
    }

    /**
     * POST /api/cs-pathway/help-requests
     * Body: {"levelKey": "toolchain-trail", "station": "Git Village Hall", "message": "I need help..."}
     */
    @PostMapping
    public ResponseEntity<?> sendHelpRequest(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody(required = false) PathwayHelpDtoRecordRequest body) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to ask your teacher for help."));
        }
        try {
            PathwayHelpEntity saved = helpRequestService.send(user.getUsername(), body);
            return ResponseEntity.ok(Map.of("sent", true, "createdAt", saved.getCreatedAt()));
        } catch (PathwayHelpExceptionTooSoon error) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(error.getRetryAfterSeconds()))
                    .body(Map.of("error", error.getMessage(), "retryAfterSeconds", error.getRetryAfterSeconds()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }
}
