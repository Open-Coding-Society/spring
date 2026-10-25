package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@JsonIgnoreProperties({"assignedGraders"})
public class AssignmentSubmission {
    public static final int FIRST_REVISION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Assignment assignment;

    @ManyToOne
    @JoinColumn(name = "submitter_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonManagedReference(value = "submitter-submissions")
    private Submitter submitter;

    @ManyToMany
    @JoinTable(
        name = "assignment_submission_graders",
        joinColumns = @JoinColumn(name = "submission_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    private List<Person> assignedGraders;

    // ========== CONTENT AND GRADING STATE ==========
    // Every column in this block is updatable = false: saving the entity never writes them, so a
    // stale copy saved by any code path can't revert a grade or the content. They change only
    // through the conditional UPDATEs in AssignmentSubmissionJPA, which SubmissionGradingService
    // runs (one place for the grading policy, and one history entry per change).
    //
    // Three grades are kept apart:
    //   aiGrade      - the AI's latest grade for the current revision, kept even when overridden
    //   manualGrade  - a person's grade, present only while manualOverride is true
    //   grade        - the effective grade everyone sees: manualGrade if overridden, else aiGrade
    //
    // manualOverride is set when a teacher, student teacher or assignment creator grades by hand
    // (POST /api/submissions/grade/{id}). It is cleared when that person removes the override, and
    // when the student submits new content: an override applies to the revision the person
    // reviewed, so a revision is a new grading decision. AI grading never sets or clears it; an AI
    // grade made while it is set is stored in aiGrade/aiSummary without changing grade.

    @Convert(converter = SubmissionContentConverter.class)
    @Column(columnDefinition = "text", updatable = false)
    private Map<String, Object> content;

    // Starts at 1 and goes up each time the content changes. Every grading write names the
    // revision it applies to, so a grade computed for older content is never saved on newer.
    @Column(updatable = false)
    private Integer revision = FIRST_REVISION;

    @Column(updatable = false)
    private Double grade;

    @Column(columnDefinition = "text", updatable = false)
    private String feedback;

    @Column(updatable = false)
    private Double aiGrade;

    // The AI's full feedback for the current revision.
    @Column(columnDefinition = "text", updatable = false)
    private String aiSummary;

    @Column(updatable = false)
    private Integer qualityScore;

    @Column(updatable = false)
    private Boolean manualOverride = false;

    @Column(updatable = false)
    private Double manualGrade;

    @Column(columnDefinition = "text", updatable = false)
    private String manualFeedback;

    // True when the AI should (re-)grade this revision: it failed, or used the default rubric
    // because the assignment's AI rubric wasn't ready. Never true while manualOverride is.
    @Column(updatable = false)
    private Boolean needsAiRegrade;

    // When the effective grade last changed; drives "recent grade changes" in the gradebook.
    @Column(updatable = false)
    private LocalDateTime gradedAt;

    private String comment;

    private Long assignmentid;

    private Boolean isLate;

    // ========== SELF-ASSESSMENT (required on every submission) ==========
    // 1-5 ratings; range is enforced server-side in the controllers/upload
    // service rather than with a DB constraint, so older rows (submitted
    // before this feature existed) can keep these columns null.
    private Integer technicalExcellence;
    private Integer communication;
    private Integer workHabits;
    private Integer aiOrchestration;

    @Column(columnDefinition = "text")
    private String selfAssessmentReflection;

    public AssignmentSubmission(Assignment assignment, Submitter submitter, Map<String, Object> content, String comment, boolean isLate) {
        this.assignment = assignment;
        this.submitter = submitter;
        this.content = content;
        this.grade = null;
        this.feedback = null;
        this.comment = comment;
        this.assignmentid = assignment.getId();
        this.isLate = isLate;
    }

    public boolean hasManualOverride() {
        return Boolean.TRUE.equals(manualOverride);
    }

    public int currentRevision() {
        return revision == null ? FIRST_REVISION : revision;
    }

    // Getter for assignment_id (foreign key column)
    public Long getAssignmentId2() {
        return assignment != null ? assignment.getId() : null;
    }

    /**
     * Validates the required self-assessment fields shared by every submission
     * entry point. Returns a human-readable error message if something is
     * missing or out of range, or null if the values are acceptable.
     */
    public static String validateSelfAssessment(
            Integer technicalExcellence,
            Integer communication,
            Integer workHabits,
            Integer aiOrchestration,
            String selfAssessmentReflection) {
        if (!isValidRating(technicalExcellence)) {
            return "technicalExcellence is required and must be between 1 and 5";
        }
        if (!isValidRating(communication)) {
            return "communication is required and must be between 1 and 5";
        }
        if (!isValidRating(workHabits)) {
            return "workHabits is required and must be between 1 and 5";
        }
        if (!isValidRating(aiOrchestration)) {
            return "aiOrchestration is required and must be between 1 and 5";
        }
        if (selfAssessmentReflection == null || selfAssessmentReflection.trim().isEmpty()) {
            return "selfAssessmentReflection is required";
        }
        return null;
    }

    private static boolean isValidRating(Integer value) {
        return value != null && value >= 1 && value <= 5;
    }
}