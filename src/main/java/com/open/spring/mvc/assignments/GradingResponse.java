package com.open.spring.mvc.assignments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The API body for every grading endpoint: a typed outcome plus the grading state afterwards.
 * The frontend decides what to show for each outcome, so no user-facing text is built here.
 *
 * @param detail the AI grader's own explanation when it couldn't grade; null otherwise
 */
public record GradingResponse(GradingOutcome outcome, SubmissionGradingState grading, String detail) {

    public static ResponseEntity<GradingResponse> from(GradingResult result) {
        return ResponseEntity.status(statusOf(result.outcome()))
                .body(new GradingResponse(result.outcome(), result.state(), result.detail()));
    }

    /** The single mapping from grading outcomes to HTTP statuses. */
    static HttpStatus statusOf(GradingOutcome outcome) {
        return switch (outcome) {
            case MANUAL_GRADE_APPLIED, MANUAL_OVERRIDE_CLEARED, AI_GRADE_APPLIED, MANUAL_OVERRIDE_KEPT,
                    REVISION_CREATED, CONTENT_UNCHANGED -> HttpStatus.OK;
            case STALE_REVISION, NO_MANUAL_OVERRIDE -> HttpStatus.CONFLICT;
            case INVALID_GRADE -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case AI_NOT_GRADEABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_FAILED -> HttpStatus.BAD_GATEWAY;
            case SUBMISSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
