package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the assignment_submission_grade_event table: an append-only entry in a submission's
 * grading history, written in the same transaction as the change it records. submissionId is not
 * a foreign key, so history outlives a deleted submission.
 *
 * The API sends AssignmentSubmissionAPIController's GradeEventView, not this class. The table is
 * initialized by SubmissionGradeEventTableInitializer (ddl-auto is none).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assignment_submission_grade_event",
        indexes = @Index(name = "idx_grade_event_submission", columnList = "submission_id"))
public class SubmissionGradeEventEntity {

    public enum Type {
        SUBMITTED,
        REVISED,
        AI_GRADED,
        MANUAL_OVERRIDE_SET,
        MANUAL_OVERRIDE_CLEARED,
        MANUAL_OVERRIDE_KEPT_BY_REVISION,
        // Older rows only: revisions released the override before it carried over.
        MANUAL_OVERRIDE_RELEASED_BY_REVISION,
        FINALIZED,
        REVIEW_NO_CHANGE,
        AUTHORIZED_CORRECTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long submissionId;

    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private Type type;

    private Double grade;

    @Column(columnDefinition = "text")
    private String feedback;

    // Whether this grade became the effective grade.
    private Boolean effective;

    // The person who acted; null for the AI.
    private Long actorId;

    // Set for review decisions and authorized corrections.
    private Long reviewId;

    @Convert(converter = SubmissionContentConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> content;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    static SubmissionGradeEventEntity of(Long submissionId, int revision, Type type, LocalDateTime at) {
        SubmissionGradeEventEntity event = new SubmissionGradeEventEntity();
        event.setSubmissionId(submissionId);
        event.setRevision(revision);
        event.setType(type);
        event.setCreatedAt(at);
        return event;
    }
}
