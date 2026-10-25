package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A read-only snapshot of a submission's grading columns, read straight from the database
 * (see {@link AssignmentSubmissionJPA#findGradingState}) so it is never a stale in-memory copy.
 *
 * @param effectiveGrade    the grade students and the gradebook see: the manual grade while an
 *                          override is active, otherwise the AI grade
 * @param aiFeedback        the AI's full feedback (stored in the ai_summary column)
 * @param manualOverride    true while a person's grade is effective; see {@link AssignmentSubmission}
 *                          for when it is set and cleared
 */
public record SubmissionGradingState(
        Long submissionId,
        Integer revision,
        Double effectiveGrade,
        String effectiveFeedback,
        LocalDateTime gradedAt,
        Double aiGrade,
        String aiFeedback,
        Integer qualityScore,
        Boolean manualOverride,
        Double manualGrade,
        String manualFeedback,
        Boolean needsAiRegrade) {

    public SubmissionGradingState {
        // Rows from before revisions and overrides existed are backfilled, but stay defensive.
        revision = revision == null ? AssignmentSubmission.FIRST_REVISION : revision;
        manualOverride = Boolean.TRUE.equals(manualOverride);
        needsAiRegrade = Boolean.TRUE.equals(needsAiRegrade);
    }

    public static SubmissionGradingState of(AssignmentSubmission submission) {
        return new SubmissionGradingState(submission.getId(), submission.getRevision(), submission.getGrade(),
                submission.getFeedback(), submission.getGradedAt(), submission.getAiGrade(),
                submission.getAiSummary(), submission.getQualityScore(), submission.getManualOverride(),
                submission.getManualGrade(), submission.getManualFeedback(), submission.getNeedsAiRegrade());
    }

    @JsonProperty("source")
    public GradeSource source() {
        if (manualOverride) {
            return GradeSource.MANUAL;
        }
        return effectiveGrade == null ? GradeSource.NONE : GradeSource.AI;
    }

    /**
     * Mirrors this state onto an in-memory entity so callers building a response see current
     * values. The grading columns are not updatable through the entity, so this never persists.
     */
    void copyTo(AssignmentSubmission submission) {
        submission.setRevision(revision);
        submission.setGrade(effectiveGrade);
        submission.setFeedback(effectiveFeedback);
        submission.setGradedAt(gradedAt);
        submission.setAiGrade(aiGrade);
        submission.setAiSummary(aiFeedback);
        submission.setQualityScore(qualityScore);
        submission.setManualOverride(manualOverride);
        submission.setManualGrade(manualGrade);
        submission.setManualFeedback(manualFeedback);
        submission.setNeedsAiRegrade(needsAiRegrade);
    }
}
