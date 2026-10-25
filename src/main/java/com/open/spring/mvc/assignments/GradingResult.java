package com.open.spring.mvc.assignments;

/**
 * What a grading action did, plus the submission's grading state afterwards.
 *
 * @param state  the state read back after the action; null only when the submission is gone
 * @param detail the AI grader's own explanation for {@link GradingOutcome#AI_NOT_GRADEABLE} and
 *               {@link GradingOutcome#AI_FAILED}; null otherwise
 */
public record GradingResult(GradingOutcome outcome, SubmissionGradingState state, String detail) {

    public static GradingResult of(GradingOutcome outcome, SubmissionGradingState state) {
        return new GradingResult(outcome, state, null);
    }

    public static GradingResult notFound() {
        return new GradingResult(GradingOutcome.SUBMISSION_NOT_FOUND, null, null);
    }

    public static GradingResult notPermitted() {
        return new GradingResult(GradingOutcome.NOT_PERMITTED, null, null);
    }
}
