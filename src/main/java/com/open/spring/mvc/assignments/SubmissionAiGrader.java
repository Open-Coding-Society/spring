package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the AI grader on a submission and records the result through
 * {@link SubmissionGradingService}. The AI call takes seconds, so it runs outside any transaction;
 * the result is saved against the revision that was graded, which is what keeps a teacher's grade
 * or a student's newer content from being overwritten when the AI finishes.
 *
 * Callers must pass a submission whose assignment is loaded (not a lazy proxy outside a session),
 * because the grader reads the assignment's rubric.
 */
@Service
public class SubmissionAiGrader {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionAiGrader.class);

    private final AssignmentAiGradingService aiGradingService;
    private final SubmissionGradingService gradingService;

    public SubmissionAiGrader(AssignmentAiGradingService aiGradingService, SubmissionGradingService gradingService) {
        this.aiGradingService = aiGradingService;
        this.gradingService = gradingService;
    }

    /**
     * Grades the submission's current revision and records the result. The entity is updated in
     * memory with the saved grading state.
     */
    public GradingResult grade(AssignmentSubmission submission) {
        int revision = submission.currentRevision();
        AssignmentAiGradingService.GradeResult result;
        try {
            result = aiGradingService.grade(submission);
        } catch (Exception e) {
            logger.warn("AI grading failed submission={} failureType={}", submission.getId(), e.getClass().getSimpleName());
            return failure(GradingOutcome.AI_FAILED, submission,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (result.isGraded()) {
            GradingResult recorded = gradingService.recordAiGrade(submission.getId(), revision, result);
            if (recorded.state() != null) {
                recorded.state().copyTo(submission);
            }
            logger.info("AI grade for submission={} revision={}: {}", submission.getId(), revision, recorded.outcome());
            return recorded;
        }
        GradingOutcome outcome = "not_gradeable".equals(result.status())
                ? GradingOutcome.AI_NOT_GRADEABLE : GradingOutcome.AI_FAILED;
        return failure(outcome, submission, result.message());
    }

    /**
     * Best-effort grading right after a submit or a revision. A failed grade never fails the
     * request: the work is saved either way and the revision is queued for an AI retry.
     */
    public GradingResult autoGrade(AssignmentSubmission submission) {
        GradingResult result = grade(submission);
        if (result.outcome() == GradingOutcome.AI_FAILED
                && gradingService.flagForAiRegrade(submission.getId(), submission.currentRevision())) {
            gradingService.refresh(submission);
        }
        return result;
    }

    private GradingResult failure(GradingOutcome outcome, AssignmentSubmission submission, String detail) {
        return new GradingResult(outcome, gradingService.currentState(submission.getId()).orElse(null), detail);
    }
}
