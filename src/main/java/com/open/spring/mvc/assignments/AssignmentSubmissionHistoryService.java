package com.open.spring.mvc.assignments;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps one submission per assignment and submitter (a person or a group). A resubmit copies the
 * current version into AssignmentSubmissionHistory and then overwrites the same row, so its id,
 * assigned graders and AI queue entry stay attached to the latest work.
 *
 * Rows saved before this existed were never overwritten: a resubmit added another row. Those rows
 * are left as they are. The newest row per assignment and submitter is the current submission and
 * the older ones count as its earlier versions; see {@link #latestOnly} and {@link #currentRowSql}.
 *
 * Also owns the history table and submitted_at column migration (the schema uses ddl-auto=none).
 */
@Service
public class AssignmentSubmissionHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionHistoryService.class);

    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentSubmissionHistoryJPA historyRepo;
    private final AssignmentAiGradingService aiGradingService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    // Two submits for the same assignment at once (a double click, or a multi-file upload) must not
    // both see "no submission yet" and insert two rows, so the find-then-save step runs one at a time.
    private final Object submitLock = new Object();

    public AssignmentSubmissionHistoryService(
            AssignmentSubmissionJPA submissionRepo,
            AssignmentSubmissionHistoryJPA historyRepo,
            AssignmentAiGradingService aiGradingService,
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource) {
        this.submissionRepo = submissionRepo;
        this.historyRepo = historyRepo;
        this.aiGradingService = aiGradingService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /**
     * SQL condition that is true when {@code alias} is the current submission, i.e. no newer row
     * exists for the same assignment and submitter. For queries over assignment_submission.
     */
    public static String currentRowSql(String alias) {
        return "NOT EXISTS (SELECT 1 FROM assignment_submission newer WHERE newer.assignment_id = "
                + alias + ".assignment_id AND newer.submitter_id = " + alias + ".submitter_id AND newer.id > "
                + alias + ".id)";
    }

    /**
     * Adds the submitted_at column and creates the history table; runs before other startup
     * runners that load submissions. Only adds things, so existing rows are untouched.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner migrateSubmissionHistory() {
        return args -> {
            try {
                jdbcTemplate.execute("ALTER TABLE assignment_submission ADD COLUMN submitted_at DATETIME");
                logger.info("Added submitted_at column to assignment_submission");
            } catch (DataAccessException e) {
                logger.debug("submitted_at column not added (already exists?): {}", e.getMessage());
            }
            createHistoryTable();
        };
    }

    private void createHistoryTable() {
        String idColumn;
        try (Connection connection = dataSource.getConnection()) {
            idColumn = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
                    ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                    : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        } catch (Exception e) {
            logger.error("Could not create assignment_submission_history: {}", e.getMessage());
            return;
        }
        // No foreign key: deleting a submission through JPA removes its history (cascade on
        // AssignmentSubmission.history), and a leftover row of a deleted submission is never read.
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS assignment_submission_history (" + idColumn + ", "
                + "submission_id BIGINT NOT NULL, "
                + "content TEXT, comment TEXT, is_late BOOLEAN, "
                + "submitted_at DATETIME, replaced_at DATETIME, "
                + "grade DOUBLE, feedback TEXT, ai_summary TEXT, quality_score INT, graded_at DATETIME, "
                + "technical_excellence INT, communication INT, work_habits INT, ai_orchestration INT, "
                + "self_assessment_reflection TEXT)");
        try {
            jdbcTemplate.execute("CREATE INDEX idx_submission_history_submission "
                    + "ON assignment_submission_history (submission_id)");
        } catch (DataAccessException e) {
            logger.debug("History index not created (already exists?): {}", e.getMessage());
        }
    }

    /** The saved current submission, and whether it replaced an earlier one rather than being new. */
    public record SubmitResult(AssignmentSubmission submission, boolean replaced) {
    }

    /**
     * Saves {@code draft} as the submitter's submission for its assignment. With no submission yet
     * it is inserted; otherwise the current version moves to history and the existing row takes the
     * draft's work, with its grade cleared for grading again. The returned submission keeps the
     * draft's loaded assignment so callers can grade it and build a response outside a session.
     */
    public SubmitResult submit(AssignmentSubmission draft) {
        synchronized (submitLock) {
            // Read outside the transaction: on SQLite (WAL) a transaction that reads and then writes
            // fails with SQLITE_BUSY_SNAPSHOT when Hibernate's id generator commits on its own
            // connection in between. The lock above already keeps two submits from racing.
            List<AssignmentSubmission> existing = rowsNewestFirst(key(draft));
            LocalDateTime now = now();
            draft.setSubmittedAt(now);
            if (existing.isEmpty()) {
                return new SubmitResult(submissionRepo.save(draft), false);
            }
            AssignmentSubmission current = existing.get(0);
            AssignmentSubmissionHistory earlier = AssignmentSubmissionHistory.snapshotOf(current, now);
            current.setContent(draft.getContent());
            current.setComment(draft.getComment());
            current.setIsLate(draft.getIsLate());
            current.setSubmittedAt(now);
            current.setTechnicalExcellence(draft.getTechnicalExcellence());
            current.setCommunication(draft.getCommunication());
            current.setWorkHabits(draft.getWorkHabits());
            current.setAiOrchestration(draft.getAiOrchestration());
            current.setSelfAssessmentReflection(draft.getSelfAssessmentReflection());
            clearGrade(current);
            current.setAssignment(draft.getAssignment());
            // Both writes or neither, so a version is never lost or duplicated.
            transactionTemplate.executeWithoutResult(status -> {
                historyRepo.save(earlier);
                submissionRepo.save(current);
            });
            logger.info("Resubmission replaced submission={} (earlier version kept in history)", current.getId());
            return new SubmitResult(current, true);
        }
    }

    /**
     * Copies the submission's current version into history before an edit replaces its work.
     * Call inside the edit's transaction, before changing the submission.
     */
    public void saveVersionBeforeEdit(AssignmentSubmission submission) {
        LocalDateTime now = now();
        historyRepo.save(AssignmentSubmissionHistory.snapshotOf(submission, now));
        submission.setSubmittedAt(now);
    }

    // Whole seconds: MySQL DATETIME drops fractions, and autoGrade compares this with the stored value.
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Clears the grade and AI results of work that was just replaced, so it is graded again. */
    public static void clearGrade(AssignmentSubmission submission) {
        submission.setGrade(null);
        submission.setFeedback(null);
        submission.setAiSummary(null);
        submission.setQualityScore(null);
        submission.setNeedsAiRegrade(null);
        submission.setGradedAt(null);
    }

    /**
     * AI-grades freshly submitted work, best-effort; a failure flags it for
     * AssignmentAiRegradeService to retry and never fails the submission. Grading takes seconds, so
     * if the student resubmitted meanwhile, the stale result is dropped instead of being written
     * over the newer work. Returns the caller's instance, updated with the grade when one applied.
     */
    public AssignmentSubmission autoGrade(AssignmentSubmission submission) {
        try {
            AssignmentAiGradingService.GradeResult result = aiGradingService.grade(submission);
            if ("graded".equals(result.status())) {
                if (applyIfStillCurrent(submission, result::applyTo)) {
                    // Also the caller's instance: its assignment is loaded, unlike a reloaded copy's
                    // lazy proxy, so the response DTO can be built outside a session.
                    result.applyTo(submission);
                    logger.info("Auto-grade save completed submission={} grade={}", submission.getId(), submission.getGrade());
                }
            } else if ("failed".equals(result.status())) {
                flagForRegrade(submission);
            }
        } catch (Exception e) {
            logger.warn("Auto-grade failed submission={} failureType={}; saved work retained",
                    submission.getId(), e.getClass().getSimpleName());
            flagForRegrade(submission);
        }
        return submission;
    }

    /** Lets AssignmentAiRegradeService retry a grade that failed (e.g. Gemini timed out). */
    private void flagForRegrade(AssignmentSubmission submission) {
        if (applyIfStillCurrent(submission, latest -> latest.setNeedsAiRegrade(true))) {
            submission.setNeedsAiRegrade(true);
        }
    }

    /**
     * Applies {@code change} to the stored submission and saves it, unless the student resubmitted
     * since {@code graded} was loaded (every submit sets a new submittedAt). Returns whether it applied.
     */
    private boolean applyIfStillCurrent(AssignmentSubmission graded, Consumer<AssignmentSubmission> change) {
        AssignmentSubmission latest = submissionRepo.findById(graded.getId()).orElse(null);
        if (latest == null || !Objects.equals(latest.getSubmittedAt(), graded.getSubmittedAt())) {
            logger.info("Auto-grade result dropped submission={}: resubmitted while grading", graded.getId());
            return false;
        }
        change.accept(latest);
        submissionRepo.save(latest);
        return true;
    }

    /** Keeps the newest row per assignment and submitter, in the given order. */
    public static List<AssignmentSubmission> latestOnly(Collection<AssignmentSubmission> submissions) {
        Map<List<Long>, AssignmentSubmission> newest = new LinkedHashMap<>();
        for (AssignmentSubmission submission : submissions) {
            List<Long> key = key(submission);
            if (key != null) {
                newest.merge(key, submission, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        return submissions.stream()
                .filter(submission -> key(submission) == null || newest.get(key(submission)) == submission)
                .toList();
    }

    /** Assignment and submitter ids, or null when either is missing (such a row is always its own). */
    public static List<Long> key(AssignmentSubmission submission) {
        // getId() on the lazy assignment proxy doesn't load it.
        Long assignmentId = submission.getAssignment() != null ? submission.getAssignment().getId() : submission.getAssignmentid();
        Long submitterId = submission.getSubmitter() == null ? null : submission.getSubmitter().getId();
        return assignmentId == null || submitterId == null ? null : Arrays.asList(assignmentId, submitterId);
    }

    /** Every row for one assignment and submitter, newest first (several only in data from before history). */
    private List<AssignmentSubmission> rowsNewestFirst(List<Long> key) {
        return submissionRepo.findBySubmitterId(key.get(1)).stream()
                .filter(row -> key.equals(key(row)))
                .sorted(Comparator.comparing(AssignmentSubmission::getId).reversed())
                .toList();
    }

    /**
     * One version of a submission. {@code current} marks the latest; {@code historyId} is set for
     * versions saved by a resubmit, and earlier rows from before history existed carry their own
     * {@code submissionId} instead.
     */
    public record Version(Long submissionId, Long historyId, boolean current, LocalDateTime submittedAt,
            LocalDateTime replacedAt, Map<String, Object> content, String comment, Boolean isLate, Double grade,
            String feedback, String aiSummary, Integer qualityScore, LocalDateTime gradedAt) {
    }

    /** Every version of a submission, the current one first, then newest to oldest. */
    public record SubmissionHistory(Long submissionId, Long assignmentId, String assignmentName, Double points,
            List<Version> versions) {
    }

    /**
     * The current submission for {@code submission}'s assignment and submitter. Usually the
     * submission itself; for an older duplicate row, the newer row that superseded it.
     */
    public AssignmentSubmission currentOf(AssignmentSubmission submission) {
        List<Long> key = key(submission);
        return key == null ? submission : rowsNewestFirst(key).stream().findFirst().orElse(submission);
    }

    /** The full history of the current submission {@code current} (see {@link #currentOf}). */
    @Transactional(readOnly = true)
    public SubmissionHistory historyOf(AssignmentSubmission current) {
        List<Version> versions = new ArrayList<>();
        versions.add(versionOf(current, true));
        historyRepo.findBySubmissionIdOrderByIdDesc(current.getId()).forEach(saved -> versions.add(new Version(
                current.getId(), saved.getId(), false, saved.getSubmittedAt(), saved.getReplacedAt(),
                saved.getContent(), saved.getComment(), saved.getIsLate(), saved.getGrade(), saved.getFeedback(),
                saved.getAiSummary(), saved.getQualityScore(), saved.getGradedAt())));
        List<Long> key = key(current);
        if (key != null) {
            rowsNewestFirst(key).stream()
                    .filter(older -> older.getId() < current.getId())
                    .forEach(older -> versions.add(versionOf(older, false)));
        }
        Assignment assignment = current.getAssignment();
        return new SubmissionHistory(current.getId(), assignment == null ? null : assignment.getId(),
                assignment == null ? "(deleted assignment)" : assignment.getName(),
                assignment == null ? null : assignment.getPoints(), versions);
    }

    private static Version versionOf(AssignmentSubmission submission, boolean current) {
        return new Version(submission.getId(), null, current, submission.getSubmittedAt(), null,
                submission.getContent(), submission.getComment(), submission.getIsLate(), submission.getGrade(),
                submission.getFeedback(), submission.getAiSummary(), submission.getQualityScore(),
                submission.getGradedAt());
    }
}
