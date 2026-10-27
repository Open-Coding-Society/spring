package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.Map;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * An earlier version of an assignment submission. When a student resubmits, the submission row
 * keeps its id and is overwritten with the new work; what it held before (the work and the grade
 * it got) is copied here first. The table is created by AssignmentSubmissionHistoryService
 * (ddl-auto is none).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "assignment_submission_history")
public class AssignmentSubmissionHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The submission this was an earlier version of; it always holds the latest version. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private AssignmentSubmission submission;

    @Convert(converter = SubmissionContentConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> content;

    @Column(columnDefinition = "text")
    private String comment;

    private Boolean isLate;

    // Null for versions submitted before submission times were recorded.
    private LocalDateTime submittedAt;

    // When the student resubmitted and this version stopped being the current one.
    private LocalDateTime replacedAt;

    // The grade this version had when it was replaced (null if it was never graded).
    private Double grade;

    @Column(columnDefinition = "text")
    private String feedback;

    @Column(columnDefinition = "text")
    private String aiSummary;

    private Integer qualityScore;

    private LocalDateTime gradedAt;

    private Integer technicalExcellence;
    private Integer communication;
    private Integer workHabits;
    private Integer aiOrchestration;

    @Column(columnDefinition = "text")
    private String selfAssessmentReflection;

    /** Copies what the submission holds right now, just before it is replaced. */
    public static AssignmentSubmissionHistory snapshotOf(AssignmentSubmission submission, LocalDateTime replacedAt) {
        AssignmentSubmissionHistory version = new AssignmentSubmissionHistory();
        version.setSubmission(submission);
        version.setContent(submission.getContent());
        version.setComment(submission.getComment());
        version.setIsLate(submission.getIsLate());
        version.setSubmittedAt(submission.getSubmittedAt());
        version.setReplacedAt(replacedAt);
        version.setGrade(submission.getGrade());
        version.setFeedback(submission.getFeedback());
        version.setAiSummary(submission.getAiSummary());
        version.setQualityScore(submission.getQualityScore());
        version.setGradedAt(submission.getGradedAt());
        version.setTechnicalExcellence(submission.getTechnicalExcellence());
        version.setCommunication(submission.getCommunication());
        version.setWorkHabits(submission.getWorkHabits());
        version.setAiOrchestration(submission.getAiOrchestration());
        version.setSelfAssessmentReflection(submission.getSelfAssessmentReflection());
        return version;
    }
}
