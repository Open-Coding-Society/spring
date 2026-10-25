package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

/**
 * The one place that changes a submission's content or grading state, and records each change
 * in its history ({@link SubmissionGradeEvent}) in the same transaction.
 *
 * The policy, in full:
 * <ul>
 *   <li>The AI grade is effective unless a person has overridden it.</li>
 *   <li>A manual grade becomes effective at once and stays effective through any AI grading; the
 *       AI grade and feedback are still saved alongside it for reference.</li>
 *   <li>New content is a new revision and a new grading decision: its AI grade starts empty and any
 *       manual override is released, because the person reviewed the previous revision. The old
 *       revision's grades stay in the history.</li>
 *   <li>Every write names the revision it applies to, so a grade computed or reviewed for older
 *       content is rejected as {@link GradingOutcome#STALE_REVISION} instead of being saved.</li>
 * </ul>
 *
 * Concurrency: each write is a conditional UPDATE in {@link AssignmentSubmissionJPA}, and ordinary
 * entity saves can't touch these columns at all (they are mapped updatable = false). Methods that
 * read before deciding what to write first take the submission's write lock
 * ({@link AssignmentSubmissionJPA#lockForGrading}), so what they read can't change underneath them.
 * They also load and authorize the submission themselves, so callers don't need a transaction of
 * their own (one that has already read would turn the write into a SQLite read-to-write upgrade).
 *
 * Nothing here calls the AI; {@link SubmissionAiGrader} does that outside any transaction and then
 * records the result here, so a slow AI call never holds a database lock.
 */
@Service
public class SubmissionGradingService {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionGradingService.class);

    private final AssignmentSubmissionJPA submissionRepo;
    private final SubmissionGradeEventJPA eventRepo;
    private final AssignmentAuthorizationService authorizationService;

    public SubmissionGradingService(AssignmentSubmissionJPA submissionRepo, SubmissionGradeEventJPA eventRepo,
            AssignmentAuthorizationService authorizationService) {
        this.submissionRepo = submissionRepo;
        this.eventRepo = eventRepo;
        this.authorizationService = authorizationService;
    }

    /** Saves a new submission as revision 1 and records what was submitted. */
    @Transactional
    public AssignmentSubmission submit(AssignmentSubmission submission, Person submittedBy) {
        submission.setRevision(AssignmentSubmission.FIRST_REVISION);
        submission.setManualOverride(false);
        AssignmentSubmission saved = submissionRepo.save(submission);
        SubmissionGradeEvent event = event(saved.getId(), AssignmentSubmission.FIRST_REVISION,
                SubmissionGradeEvent.Type.SUBMITTED, submittedBy);
        event.setContent(saved.getContent());
        eventRepo.save(event);
        return saved;
    }

    /**
     * Saves an edit. The caller sets the ordinary fields (comment, late flag) on the entity and has
     * already checked the editor may edit it; if {@code newContent} differs from the content the
     * entity was loaded with, it becomes the next revision.
     *
     * @return {@link GradingOutcome#REVISION_CREATED}, {@link GradingOutcome#CONTENT_UNCHANGED}, or
     *         {@link GradingOutcome#STALE_REVISION} if someone else revised it first
     */
    @Transactional
    public GradingResult revise(AssignmentSubmission submission, Map<String, Object> newContent, Person editor) {
        if (submissionRepo.lockForGrading(submission.getId()) == 0) {
            return GradingResult.notFound();
        }
        submissionRepo.save(submission);
        SubmissionGradingState before = submissionRepo.findGradingState(submission.getId()).orElseThrow();
        if (Objects.equals(submission.getContent(), newContent)) {
            return GradingResult.of(GradingOutcome.CONTENT_UNCHANGED, before);
        }
        int revision = submission.currentRevision();
        if (submissionRepo.startRevision(submission.getId(), revision, newContent) == 0) {
            return GradingResult.of(GradingOutcome.STALE_REVISION, before);
        }

        if (before.manualOverride()) {
            SubmissionGradeEvent released = event(submission.getId(), revision,
                    SubmissionGradeEvent.Type.MANUAL_OVERRIDE_RELEASED_BY_REVISION, editor);
            released.setGrade(before.manualGrade());
            released.setFeedback(before.manualFeedback());
            released.setEffective(false);
            eventRepo.save(released);
        }
        SubmissionGradeEvent revised = event(submission.getId(), revision + 1, SubmissionGradeEvent.Type.REVISED, editor);
        revised.setContent(newContent);
        eventRepo.save(revised);

        submission.setContent(newContent);
        return reloaded(GradingOutcome.REVISION_CREATED, submission);
    }

    /**
     * Makes a person's grade the effective grade, if they may grade the submission.
     *
     * @param expectedRevision the revision the person was looking at, or null to grade whatever
     *                         is current (older clients don't send it)
     */
    @Transactional
    public GradingResult applyManualGrade(Long submissionId, Person grader, Integer expectedRevision,
            Double grade, String feedback) {
        Optional<AssignmentSubmission> found = lockAndLoad(submissionId);
        if (found.isEmpty()) {
            return GradingResult.notFound();
        }
        AssignmentSubmission submission = found.get();
        if (!authorizationService.canManageSubmission(grader, submission)) {
            return GradingResult.notPermitted();
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        if (expectedRevision != null && !expectedRevision.equals(state.revision())) {
            return GradingResult.of(GradingOutcome.STALE_REVISION, state);
        }
        if (!isValidGrade(grade, submission.getAssignment())) {
            return GradingResult.of(GradingOutcome.INVALID_GRADE, state);
        }
        submissionRepo.applyManualOverride(submissionId, state.revision(), grade, feedback, now());

        SubmissionGradeEvent event = event(submissionId, state.revision(), SubmissionGradeEvent.Type.MANUAL_OVERRIDE_SET, grader);
        event.setGrade(grade);
        event.setFeedback(feedback);
        event.setEffective(true);
        eventRepo.save(event);
        return reloaded(GradingOutcome.MANUAL_GRADE_APPLIED, submission);
    }

    /**
     * Removes a manual override so grading returns to the AI, if the person may grade the
     * submission. If the AI hasn't graded this revision yet, it is queued for grading.
     */
    @Transactional
    public GradingResult clearManualOverride(Long submissionId, Person actor, Integer expectedRevision) {
        Optional<AssignmentSubmission> found = lockAndLoad(submissionId);
        if (found.isEmpty()) {
            return GradingResult.notFound();
        }
        AssignmentSubmission submission = found.get();
        if (!authorizationService.canManageSubmission(actor, submission)) {
            return GradingResult.notPermitted();
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        if (expectedRevision != null && !expectedRevision.equals(state.revision())) {
            return GradingResult.of(GradingOutcome.STALE_REVISION, state);
        }
        if (!state.manualOverride()) {
            return GradingResult.of(GradingOutcome.NO_MANUAL_OVERRIDE, state);
        }
        submissionRepo.clearManualOverride(submissionId, state.revision(), now());
        if (state.aiGrade() == null) {
            submissionRepo.flagForAiRegrade(submissionId, state.revision());
        }

        SubmissionGradeEvent event = event(submissionId, state.revision(), SubmissionGradeEvent.Type.MANUAL_OVERRIDE_CLEARED, actor);
        event.setGrade(state.manualGrade());
        event.setFeedback(state.manualFeedback());
        event.setEffective(false);
        eventRepo.save(event);
        return reloaded(GradingOutcome.MANUAL_OVERRIDE_CLEARED, submission);
    }

    /**
     * Records a graded AI result for the revision it was computed from. The AI grade and feedback
     * are always stored; the AI grade becomes effective only if no manual override is active.
     * A person who grades while the AI is still working therefore always wins. The first statement
     * is a write, so this is safe to call without a lock.
     */
    @Transactional
    public GradingResult recordAiGrade(Long submissionId, int revision, AssignmentAiGradingService.GradeResult result) {
        if (!result.isGraded()) {
            throw new IllegalArgumentException("Only a graded AI result can be recorded, got " + result.status());
        }
        if (submissionRepo.recordAiGrade(submissionId, revision, result.grade(), result.feedback(),
                result.qualityScore()) == 0) {
            return submissionRepo.findGradingState(submissionId)
                    .map(state -> GradingResult.of(GradingOutcome.STALE_REVISION, state))
                    .orElseGet(GradingResult::notFound);
        }
        boolean applied = submissionRepo.makeAiGradeEffective(
                submissionId, revision, result.usedDefaultRubric(), now()) == 1;

        SubmissionGradeEvent event = event(submissionId, revision, SubmissionGradeEvent.Type.AI_GRADED, null);
        event.setGrade(result.grade());
        event.setFeedback(result.feedback());
        event.setEffective(applied);
        eventRepo.save(event);
        if (!applied) {
            logger.info("Kept manual grade for submission {}; AI grade saved for reference", submissionId);
        }
        return GradingResult.of(applied ? GradingOutcome.AI_GRADE_APPLIED : GradingOutcome.MANUAL_OVERRIDE_KEPT,
                submissionRepo.findGradingState(submissionId).orElse(null));
    }

    /** Queues the revision for an AI retry; does nothing if a person has overridden its grade. */
    @Transactional
    public boolean flagForAiRegrade(Long submissionId, int revision) {
        return submissionRepo.flagForAiRegrade(submissionId, revision) == 1;
    }

    /** Takes the revision out of the AI queue. */
    @Transactional
    public void clearAiRegradeFlag(Long submissionId, int revision) {
        submissionRepo.clearAiRegradeFlag(submissionId, revision);
    }

    /** Saves a summary written by the submission summarizer; it never changes a grade. */
    @Transactional
    public Optional<SubmissionGradingState> saveAiSummary(Long submissionId, String summary, Integer qualityScore) {
        submissionRepo.saveAiSummary(submissionId, summary, qualityScore);
        return submissionRepo.findGradingState(submissionId);
    }

    @Transactional(readOnly = true)
    public Optional<SubmissionGradingState> currentState(Long submissionId) {
        return submissionRepo.findGradingState(submissionId);
    }

    /** Copies the submission's current grading state from the database onto the entity. */
    @Transactional(readOnly = true)
    public AssignmentSubmission refresh(AssignmentSubmission submission) {
        submissionRepo.findGradingState(submission.getId()).ifPresent(state -> state.copyTo(submission));
        return submission;
    }

    @Transactional(readOnly = true)
    public List<SubmissionGradeEvent> history(Long submissionId) {
        return eventRepo.findBySubmissionIdOrderByIdAsc(submissionId);
    }

    /** A grade from 0 to the assignment's points (1 when the assignment has none set). */
    static boolean isValidGrade(Double grade, Assignment assignment) {
        if (grade == null || !Double.isFinite(grade) || grade < 0) {
            return false;
        }
        Double points = assignment == null ? null : assignment.getPoints();
        return grade <= AssignmentRubricService.maxScore(points);
    }

    private Optional<AssignmentSubmission> lockAndLoad(Long submissionId) {
        if (submissionRepo.lockForGrading(submissionId) == 0) {
            return Optional.empty();
        }
        return submissionRepo.findById(submissionId);
    }

    private GradingResult reloaded(GradingOutcome outcome, AssignmentSubmission submission) {
        SubmissionGradingState state = submissionRepo.findGradingState(submission.getId()).orElse(null);
        if (state != null) {
            state.copyTo(submission);
        }
        return GradingResult.of(outcome, state);
    }

    private static SubmissionGradeEvent event(Long submissionId, int revision, SubmissionGradeEvent.Type type,
            Person actor) {
        SubmissionGradeEvent event = SubmissionGradeEvent.of(submissionId, revision, type, now());
        event.setActorId(actor == null ? null : actor.getId());
        return event;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
