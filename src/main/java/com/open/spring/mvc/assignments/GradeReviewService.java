package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;

/**
 * Reviews of a submission's grade. A teacher opens one after close for a re-grade request,
 * similarity check or petition; a student files a petition themselves, before close only against
 * a manual grade (which new revisions keep). A teacher collects evidence and decides with a
 * rationale; only an ADJUST decision changes the grade.
 */
@Service
public class GradeReviewService {

    public record ReviewResult(GradingOutcome outcome, GradeReviewEntity review, SubmissionGradingState grading,
            String detail) {
        static ReviewResult of(GradingOutcome outcome) {
            return new ReviewResult(outcome, null, null, null);
        }

        static ReviewResult of(GradingOutcome outcome, GradeReviewEntity review, SubmissionGradingState grading) {
            return new ReviewResult(outcome, review, grading, null);
        }
    }

    /** The submission to re-evaluate with its assignment loaded, or why it can't be. */
    record AiEvaluationTarget(GradeReviewEntity review, AssignmentSubmission submission, GradingOutcome refusal) {
        static AiEvaluationTarget refused(GradingOutcome refusal) {
            return new AiEvaluationTarget(null, null, refusal);
        }
    }

    private final GradeReviewJpaRepository reviewRepo;
    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final SubmissionGradingService gradingService;
    private final FinalGradeTransfer gradeTransfer;
    private final AssignmentAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public GradeReviewService(GradeReviewJpaRepository reviewRepo, AssignmentSubmissionJPA submissionRepo,
            AssignmentJpaRepository assignmentRepo, SubmissionGradingService gradingService,
            FinalGradeTransfer gradeTransfer, AssignmentAuthorizationService authorizationService,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.reviewRepo = reviewRepo;
        this.submissionRepo = submissionRepo;
        this.assignmentRepo = assignmentRepo;
        this.gradingService = gradingService;
        this.gradeTransfer = gradeTransfer;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ReviewResult open(Long submissionId, Person teacher, GradeReviewEntity.Reason reason, String evidence) {
        if (!authorizationService.isTeacherOrAdmin(teacher)) {
            return ReviewResult.of(GradingOutcome.NOT_PERMITTED);
        }
        if (reason == null) {
            return ReviewResult.of(GradingOutcome.REASON_REQUIRED);
        }
        if (submissionRepo.lockForGrading(submissionId) == 0) {
            return ReviewResult.of(GradingOutcome.SUBMISSION_NOT_FOUND);
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        if (!state.closed()) {
            return ReviewResult.of(GradingOutcome.REVIEW_REQUIRES_CLOSE, null, state);
        }
        return openReview(submissionRepo.findById(submissionId).orElseThrow(), state, reason, teacher,
                blankToNull(evidence), null);
    }

    /** A student asks for their grade to be reconsidered; a teacher decides it like any review. */
    @Transactional
    public ReviewResult petition(Long submissionId, Person student, String message) {
        if (submissionRepo.lockForGrading(submissionId) == 0) {
            return ReviewResult.of(GradingOutcome.SUBMISSION_NOT_FOUND);
        }
        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElseThrow();
        if (!authorizationService.isSubmitter(student, submission)) {
            return ReviewResult.of(GradingOutcome.NOT_PERMITTED);
        }
        if (message == null || message.isBlank()) {
            return ReviewResult.of(GradingOutcome.REASON_REQUIRED);
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        // Before close an AI grade follows each revision, so only a manual grade needs a petition.
        if (!state.closed() && !state.manualOverride()) {
            return ReviewResult.of(GradingOutcome.NO_MANUAL_OVERRIDE, null, state);
        }
        return openReview(submission, state, GradeReviewEntity.Reason.PETITION, student, null, message.trim());
    }

    /** Replaces the review's evidence notes. */
    @Transactional
    public ReviewResult recordEvidence(Long reviewId, Person teacher, String evidence) {
        return withOpenReview(reviewId, teacher, review -> {
            review.setEvidence(blankToNull(evidence));
            return evidenceRecorded(review);
        });
    }

    /** Adds the submissions with identical content to the evidence. */
    @Transactional
    public ReviewResult collectSimilarity(Long reviewId, Person teacher) {
        return withOpenReview(reviewId, teacher, review -> {
            AssignmentSubmission target = submissionRepo.findById(review.getSubmissionId()).orElseThrow();
            List<SubmissionSimilarity.Match> matches = SubmissionSimilarity.findMatches(target,
                    submissionRepo.findByAssignmentId(review.getAssignmentId()));
            try {
                review.setSimilarSubmissions(objectMapper.writeValueAsString(matches));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not store similarity evidence", e);
            }
            return evidenceRecorded(review);
        });
    }

    /** Loads what {@link SubmissionAiGrader#evaluateForReview} needs before it calls the AI. */
    @Transactional(readOnly = true)
    public AiEvaluationTarget aiEvaluationTarget(Long reviewId, Person teacher) {
        if (!authorizationService.isTeacherOrAdmin(teacher)) {
            return AiEvaluationTarget.refused(GradingOutcome.NOT_PERMITTED);
        }
        GradeReviewEntity review = reviewRepo.findById(reviewId).orElse(null);
        if (review == null) {
            return AiEvaluationTarget.refused(GradingOutcome.REVIEW_NOT_FOUND);
        }
        if (!review.isOpen()) {
            return AiEvaluationTarget.refused(GradingOutcome.REVIEW_NOT_OPEN);
        }
        AssignmentSubmission submission = submissionRepo.findById(review.getSubmissionId()).orElse(null);
        if (submission == null) {
            return AiEvaluationTarget.refused(GradingOutcome.SUBMISSION_NOT_FOUND);
        }
        assignmentRepo.findById(review.getAssignmentId()).ifPresent(submission::setAssignment);
        return new AiEvaluationTarget(review, submission, null);
    }

    /** Stores an AI re-evaluation on the review as evidence; the grade is not changed. */
    @Transactional
    public ReviewResult recordAiEvaluation(Long reviewId, Person teacher, AssignmentAiGradingService.GradeResult result) {
        return withOpenReview(reviewId, teacher, review -> {
            review.setAiGrade(result.grade());
            review.setAiFeedback(result.feedback());
            return evidenceRecorded(review);
        });
    }

    /**
     * Decides the review: NO_CHANGE keeps the grade, ADJUST applies the new one (see
     * {@link SubmissionGradingService#applyReviewedGrade}). After close the gradebook is updated once
     * the decision commits (see {@link FinalGradeTransfer}).
     */
    public ReviewResult decide(Long reviewId, Person teacher, GradeReviewEntity.Decision decision, String rationale,
            Double grade, String feedback) {
        ReviewResult result = transactions.execute(status -> withOpenReview(reviewId, teacher,
                review -> decide(review, teacher, decision, rationale, grade, feedback)));
        if (result.outcome() == GradingOutcome.GRADE_CORRECTED) {
            gradeTransfer.transfer(result.review().getAssignmentId());
        }
        return result;
    }

    /** Reviews of one submission, oldest first; empty for anyone but a teacher. */
    @Transactional(readOnly = true)
    public Optional<List<GradeReviewEntity>> reviewsOf(Long submissionId, Person teacher) {
        return forTeacher(teacher, () -> reviewRepo.findBySubmissionIdOrderByIdAsc(submissionId));
    }

    /** Open reviews on an assignment's submissions, such as petitions filed before close; teacher-only. */
    @Transactional(readOnly = true)
    public Optional<List<GradeReviewEntity>> openReviewsOf(Long assignmentId, Person teacher) {
        return forTeacher(teacher, () -> reviewRepo.findByAssignmentIdAndStatusOrderByIdAsc(
                assignmentId, GradeReviewEntity.Status.OPEN));
    }

    /** Petitions on a submission, oldest first; empty for anyone but its submitter. */
    @Transactional(readOnly = true)
    public Optional<List<GradeReviewEntity>> petitionsOf(Long submissionId, Person student) {
        return submissionRepo.findById(submissionId)
                .filter(submission -> authorizationService.isSubmitter(student, submission))
                .map(submission -> reviewRepo.findBySubmissionIdAndReasonOrderByIdAsc(
                        submissionId, GradeReviewEntity.Reason.PETITION));
    }

    private Optional<List<GradeReviewEntity>> forTeacher(Person teacher, Supplier<List<GradeReviewEntity>> reviews) {
        return authorizationService.isTeacherOrAdmin(teacher) ? Optional.of(reviews.get()) : Optional.empty();
    }

    private ReviewResult decide(GradeReviewEntity review, Person teacher, GradeReviewEntity.Decision decision, String rationale,
            Double grade, String feedback) {
        if (decision == null) {
            return ReviewResult.of(GradingOutcome.DECISION_REQUIRED);
        }
        if (rationale == null || rationale.isBlank()) {
            return ReviewResult.of(GradingOutcome.RATIONALE_REQUIRED);
        }
        if (submissionRepo.lockForGrading(review.getSubmissionId()) == 0) {
            return ReviewResult.of(GradingOutcome.SUBMISSION_NOT_FOUND);
        }
        AssignmentSubmission submission = submissionRepo.findById(review.getSubmissionId()).orElseThrow();
        if (decision == GradeReviewEntity.Decision.NO_CHANGE) {
            markDecided(review, decision, rationale, teacher);
            SubmissionGradingState state = submissionRepo.findGradingState(submission.getId()).orElseThrow();
            gradingService.recordReviewNoChange(state, review, teacher);
            return ReviewResult.of(GradingOutcome.REVIEW_NO_CHANGE, reviewRepo.save(review), state);
        }
        // The review is marked decided only after the grade is applied, so an invalid grade leaves it open.
        GradingResult applied = gradingService.applyReviewedGrade(submission, review, grade, feedback, teacher);
        if (applied.outcome() == GradingOutcome.INVALID_GRADE) {
            return ReviewResult.of(applied.outcome(), null, applied.state());
        }
        markDecided(review, decision, rationale, teacher);
        review.setAdjustedGrade(grade);
        review.setAdjustedFeedback(blankToNull(feedback));
        return ReviewResult.of(applied.outcome(), reviewRepo.save(review), applied.state());
    }

    private ReviewResult openReview(AssignmentSubmission submission, SubmissionGradingState state,
            GradeReviewEntity.Reason reason, Person opener, String evidence, String petitionMessage) {
        if (reviewRepo.existsBySubmissionIdAndStatus(submission.getId(), GradeReviewEntity.Status.OPEN)) {
            return ReviewResult.of(GradingOutcome.REVIEW_ALREADY_OPEN, null, state);
        }
        GradeReviewEntity review = new GradeReviewEntity();
        review.setSubmissionId(submission.getId());
        review.setAssignmentId(submission.getAssignment().getId());
        review.setReason(reason);
        review.setStatus(GradeReviewEntity.Status.OPEN);
        review.setGradeAtOpen(state.effectiveGrade());
        review.setEvidence(evidence);
        review.setPetitionMessage(petitionMessage);
        review.setOpenedById(opener.getId());
        review.setOpenedAt(LocalDateTime.now());
        return ReviewResult.of(GradingOutcome.REVIEW_OPENED, reviewRepo.save(review), state);
    }

    /**
     * Checks the teacher, takes the review's row lock, and runs the action if the review is still
     * open. The lock comes first so the review read afterwards is current.
     */
    private ReviewResult withOpenReview(Long reviewId, Person teacher, Function<GradeReviewEntity, ReviewResult> action) {
        if (!authorizationService.isTeacherOrAdmin(teacher)) {
            return ReviewResult.of(GradingOutcome.NOT_PERMITTED);
        }
        if (reviewRepo.lockReview(reviewId) == 0) {
            return ReviewResult.of(GradingOutcome.REVIEW_NOT_FOUND);
        }
        GradeReviewEntity review = reviewRepo.findById(reviewId).orElseThrow();
        return review.isOpen() ? action.apply(review) : ReviewResult.of(GradingOutcome.REVIEW_NOT_OPEN, review, null);
    }

    private static void markDecided(GradeReviewEntity review, GradeReviewEntity.Decision decision, String rationale, Person teacher) {
        review.setDecision(decision);
        review.setRationale(rationale.trim());
        review.setStatus(GradeReviewEntity.Status.DECIDED);
        review.setDecidedById(teacher.getId());
        review.setDecidedAt(LocalDateTime.now());
    }

    private ReviewResult evidenceRecorded(GradeReviewEntity review) {
        return ReviewResult.of(GradingOutcome.EVIDENCE_RECORDED, reviewRepo.save(review),
                submissionRepo.findGradingState(review.getSubmissionId()).orElse(null));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
