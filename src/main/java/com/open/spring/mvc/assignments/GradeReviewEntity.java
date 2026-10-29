package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the assignment_grade_review table: a review of a submission's grade with its reason,
 * evidence, and a decision with its rationale. A teacher opens one after close; a student opens a
 * PETITION, before close only against a manual grade. Changed only through {@link GradeReviewService}.
 *
 * The API sends GradeReviewApiRestController's ReviewView and PetitionView records, not this class.
 * The table is initialized by GradeReviewTableInitializer (ddl-auto is none).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "assignment_grade_review",
        indexes = {
            @Index(name = "idx_grade_review_submission", columnList = "submission_id"),
            @Index(name = "idx_grade_review_assignment", columnList = "assignment_id, status")
        })
public class GradeReviewEntity {

    public enum Reason { REGRADE_REQUEST, SIMILARITY_CHECK, PETITION }

    public enum Status { OPEN, DECIDED }

    public enum Decision { NO_CHANGE, ADJUST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long submissionId;

    @Column(nullable = false)
    private Long assignmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    // Effective grade when the review was opened.
    private Double gradeAtOpen;

    // The student's own words; set only on a petition the student filed.
    @Column(columnDefinition = "text")
    private String petitionMessage;

    // ========== Evidence ==========

    @Column(columnDefinition = "text")
    private String evidence;

    // AI re-evaluation, kept as evidence only.
    private Double aiGrade;

    @Column(columnDefinition = "text")
    private String aiFeedback;

    // JSON list of SubmissionSimilarity matches.
    @Column(columnDefinition = "text")
    private String similarSubmissions;

    // ========== Decision ==========

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Decision decision;

    @Column(columnDefinition = "text")
    private String rationale;

    // Set only for ADJUST.
    private Double adjustedGrade;

    @Column(columnDefinition = "text")
    private String adjustedFeedback;

    @Column(nullable = false)
    private Long openedById;

    @Column(nullable = false)
    private LocalDateTime openedAt;

    private Long decidedById;

    private LocalDateTime decidedAt;

    public boolean isOpen() {
        return status == Status.OPEN;
    }
}
