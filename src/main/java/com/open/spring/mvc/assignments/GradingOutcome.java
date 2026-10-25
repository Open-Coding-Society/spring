package com.open.spring.mvc.assignments;

/**
 * The result of a grading action, returned by {@link SubmissionGradingService} and
 * {@link SubmissionAiGrader}. These are expected business outcomes, not errors: the API maps each
 * one to an HTTP status in {@link GradingResponse}, and the frontend maps each one to its own text.
 */
public enum GradingOutcome {
    /** The person's grade is now the effective grade (manual override active). */
    MANUAL_GRADE_APPLIED,
    /** The manual override was removed, so the AI grade (if there is one) is effective again. */
    MANUAL_OVERRIDE_CLEARED,
    /** There was no manual override to clear. */
    NO_MANUAL_OVERRIDE,
    /** The AI grade and feedback were saved and the AI grade is now effective. */
    AI_GRADE_APPLIED,
    /** The AI grade and feedback were saved for reference; the manual override stays effective. */
    MANUAL_OVERRIDE_KEPT,
    /** New content was saved as a new revision, which starts a new grading decision. */
    REVISION_CREATED,
    /** The edit didn't change the submitted content, so grading is untouched. */
    CONTENT_UNCHANGED,
    /** The submission changed after the grade was computed or the page was loaded; nothing was written. */
    STALE_REVISION,
    /** The grade is negative or above the assignment's points. */
    INVALID_GRADE,
    /** The AI can't grade this kind of submission (e.g. a link it can't read). */
    AI_NOT_GRADEABLE,
    /** The AI grader failed or timed out; the submission is queued for a retry. */
    AI_FAILED,
    /** The person may not grade this submission; no grading state is returned. */
    NOT_PERMITTED,
    /** The submission no longer exists. */
    SUBMISSION_NOT_FOUND
}
