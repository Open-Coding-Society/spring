package com.open.spring.mvc.assignments;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GradeReviewJpaRepository extends JpaRepository<GradeReviewEntity, Long> {
    List<GradeReviewEntity> findBySubmissionIdOrderByIdAsc(Long submissionId);

    List<GradeReviewEntity> findBySubmissionIdAndReasonOrderByIdAsc(Long submissionId, GradeReviewEntity.Reason reason);

    List<GradeReviewEntity> findByAssignmentIdAndStatusOrderByIdAsc(Long assignmentId, GradeReviewEntity.Status status);

    boolean existsBySubmissionIdAndStatus(Long submissionId, GradeReviewEntity.Status status);

    /** Takes the review's row lock (see AssignmentSubmissionJPA#lockForGrading); 0 if it doesn't exist. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE GradeReviewEntity r SET r.status = r.status WHERE r.id = :id")
    int lockReview(@Param("id") Long id);
}
