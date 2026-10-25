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
 * One append-only entry in a submission's grading history: what the student submitted and when,
 * what the AI graded, and when a person overrode or released a grade. Rows are only ever inserted,
 * by {@link SubmissionGradingService}, in the same transaction as the change they describe.
 *
 * submissionId is a plain column rather than a foreign key so the audit trail outlives a deleted
 * submission. The table is created by {@link AssignmentSubmissionGradingMigration}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assignment_submission_grade_event",
        indexes = @Index(name = "idx_grade_event_submission", columnList = "submission_id"))
public class SubmissionGradeEvent {

    public enum Type {
        /** The first version of the submission; content holds what was submitted. */
        SUBMITTED,
        /** The student changed the content; content holds the new revision. */
        REVISED,
        /** The AI graded the revision; effective says whether its grade became the effective grade. */
        AI_GRADED,
        /** A person set the grade by hand; it is effective until cleared or the work is revised. */
        MANUAL_OVERRIDE_SET,
        /** A person removed their override, so the AI grade is effective again. */
        MANUAL_OVERRIDE_CLEARED,
        /** A new revision released the override, which applied to the revision the person reviewed. */
        MANUAL_OVERRIDE_RELEASED_BY_REVISION
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

    // Whether this event's grade became the effective grade; null for events without a grade.
    private Boolean effective;

    // The person who acted; null for the AI.
    private Long actorId;

    @Convert(converter = SubmissionContentConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> content;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    static SubmissionGradeEvent of(Long submissionId, int revision, Type type, LocalDateTime at) {
        SubmissionGradeEvent event = new SubmissionGradeEvent();
        event.setSubmissionId(submissionId);
        event.setRevision(revision);
        event.setType(type);
        event.setCreatedAt(at);
        return event;
    }
}
