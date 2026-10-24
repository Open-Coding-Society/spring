package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;

@Repository
public interface AssignmentSubmissionJPA extends JpaRepository<AssignmentSubmission, Long> {
    List<AssignmentSubmission> findByAssignmentId(Long assignmentId);
    List<AssignmentSubmission> findByAssignmentIdIn(Collection<Long> assignmentIds);
    List<AssignmentSubmission> findByAssignedGraders(Person grader);

    // Note: This used to just be a method signature, but now there's a query too because students is now many to many instead of many to one
    List<AssignmentSubmission> findBySubmitterId(Long id);  
    List<AssignmentSubmission> findBySubmitter(Submitter submitter);

    /**
     * Writes an AI grade only if no person has graded the submission yet. AI grading takes
     * seconds, so a teacher or creator can grade in the meantime; checking the flag in the
     * same UPDATE keeps their grade from being overwritten by the slower AI result.
     *
     * @return 1 if the AI grade was saved, 0 if a manual grade already exists
     */
    @Modifying
    @Transactional
    @Query("UPDATE AssignmentSubmission s SET s.grade = :grade, s.qualityScore = :qualityScore, "
            + "s.feedback = :feedback, s.aiSummary = :aiSummary, s.needsAiRegrade = :needsAiRegrade, "
            + "s.gradedAt = :gradedAt "
            + "WHERE s.id = :id AND (s.gradedManually IS NULL OR s.gradedManually = false)")
    int applyAiGradeUnlessManuallyGraded(
            @Param("id") Long id,
            @Param("grade") Double grade,
            @Param("qualityScore") Integer qualityScore,
            @Param("feedback") String feedback,
            @Param("aiSummary") String aiSummary,
            @Param("needsAiRegrade") Boolean needsAiRegrade,
            @Param("gradedAt") LocalDateTime gradedAt);

    /**
     * Saves a graded AI result unless the submission was graded manually, and mirrors it onto
     * the in-memory entity only when the write succeeded.
     *
     * @return true if the AI grade was saved
     */
    default boolean saveAiGradeUnlessManuallyGraded(
            AssignmentSubmission submission, AssignmentAiGradingService.GradeResult result) {
        int updated = applyAiGradeUnlessManuallyGraded(submission.getId(), result.grade(), result.qualityScore(),
                result.storedFeedback(), result.feedback(), result.usedDefaultRubric(), LocalDateTime.now());
        if (updated == 0) {
            return false;
        }
        result.applyTo(submission);
        return true;
    }
}