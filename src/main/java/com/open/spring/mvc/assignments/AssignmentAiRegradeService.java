package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PreDestroy;

/**
 * Re-grades submissions that were AI-graded with the default rubric because their
 * assignment's AI rubric wasn't ready yet. Each run first generates any missing rubric
 * (from the assignment's name/description) and then re-grades the flagged submissions
 * against it. Runs on a timer, and after auto-create queues a rubric via {@link #requestRubric}.
 *
 * Also owns the needs_ai_regrade column migration (the schema uses ddl-auto=none), and
 * uses plain SQL for its two bulk queries so the JPA repositories stay untouched.
 */
@Service
@EnableScheduling
public class AssignmentAiRegradeService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentAiRegradeService.class);

    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final AssignmentAiGradingService aiGradingService;
    private final AssignmentRubricService rubricService;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile FixGradesStatus fixStatus = new FixGradesStatus(false, 0, 0, 0, 0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "assignment-ai-regrade");
        thread.setDaemon(true);
        return thread;
    });

    public AssignmentAiRegradeService(
            AssignmentSubmissionJPA submissionRepo,
            AssignmentJpaRepository assignmentRepo,
            AssignmentAiGradingService aiGradingService,
            AssignmentRubricService rubricService,
            JdbcTemplate jdbcTemplate) {
        this.submissionRepo = submissionRepo;
        this.assignmentRepo = assignmentRepo;
        this.aiGradingService = aiGradingService;
        this.rubricService = rubricService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Adds the needs_ai_regrade column; runs before other startup runners that load submissions. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner migrateSubmissionRegradeFlag() {
        return args -> {
            try {
                jdbcTemplate.execute("ALTER TABLE assignment_submission ADD COLUMN needs_ai_regrade BOOLEAN");
                logger.info("Added needs_ai_regrade column to assignment_submission");
            } catch (DataAccessException e) {
                logger.debug("needs_ai_regrade column not added (already exists?): {}", e.getMessage());
            }
        };
    }

    /**
     * Generates the assignment's AI rubric in the background (if it still isn't ready), then
     * re-grades anything graded with the default rubric meanwhile. The Gemini call takes ~10s,
     * so doing it inside the auto-create request slowed every page view and held a SQLite
     * write lock long enough for a concurrent auto-create to fail with SQLITE_BUSY_SNAPSHOT.
     */
    public void requestRubric(Long assignmentId, String name, String description, String pageContent) {
        afterCommit(() -> {
            try {
                Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
                if (assignment == null
                        || AssignmentRubricService.isAiRubricReady(assignment.getAiRubric(), assignment.getPoints())) {
                    return; // Gone, or an earlier queued request already generated it.
                }
                String generated = rubricService.generateRubric(name, description, pageContent, assignment.getPoints());
                if (generated != null) {
                    jdbcTemplate.update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", generated, assignmentId);
                    logger.info("Generated AI rubric for assignment {}", assignmentId);
                }
            } catch (Exception e) {
                logger.warn("Background rubric generation failed for assignment {}: {}", assignmentId, e.getMessage());
            }
            runOnce();
        });
    }

    /** Runs the task on the background thread, after the caller's transaction commits if there is one. */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    @Scheduled(
            initialDelayString = "${assignments.ai-regrade.initial-delay-ms:60000}",
            fixedDelayString = "${assignments.ai-regrade.interval-ms:300000}")
    public void scheduledRun() {
        runOnce();
    }

    void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                    "SELECT id, assignment_id FROM assignment_submission WHERE needs_ai_regrade = 1 ORDER BY id");
            if (pending.isEmpty()) {
                return;
            }
            Map<Long, List<Long>> byAssignment = groupByAssignment(pending);
            logger.info("AI re-grade: {} flagged submission(s) across {} assignment(s)",
                    pending.size(), byAssignment.size());
            for (Map.Entry<Long, List<Long>> entry : byAssignment.entrySet()) {
                Assignment assignment = assignmentRepo.findById(entry.getKey()).orElse(null);
                if (assignment != null && ensureAiRubric(assignment)) {
                    entry.getValue().forEach(submissionId -> regrade(submissionId, assignment, true));
                }
            }
        } catch (Exception e) {
            logger.warn("AI re-grade run failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Progress of the latest "fix out-of-range grades" run, polled by the submissions page. */
    public record FixGradesStatus(boolean running, int total, int done, int fixed, int failed) {
    }

    public FixGradesStatus getFixStatus() {
        return fixStatus;
    }

    /**
     * Re-grades every submission whose grade is above its assignment's points (e.g. 4/1 from
     * the old 2-4 scale) against the assignment's rubric. Each submission is attempted exactly
     * once per run; a failure is counted and left alone (no flag, no retry), so it can't loop.
     */
    public synchronized FixGradesStatus startFixOutOfRangeGrades() {
        if (fixStatus.running()) {
            return fixStatus;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.assignment_id FROM assignment_submission s JOIN assignment a ON a.id = s.assignment_id "
                        + "WHERE s.grade IS NOT NULL "
                        + "AND s.grade > (CASE WHEN a.points IS NULL OR a.points <= 0 THEN 1 ELSE a.points END) + 0.000001 "
                        + "ORDER BY s.assignment_id, s.id");
        Map<Long, List<Long>> byAssignment = groupByAssignment(rows);
        fixStatus = new FixGradesStatus(!rows.isEmpty(), rows.size(), 0, 0, 0);
        if (!rows.isEmpty()) {
            executor.execute(() -> fixOutOfRangeGrades(byAssignment, rows.size()));
        }
        return fixStatus;
    }

    private void fixOutOfRangeGrades(Map<Long, List<Long>> byAssignment, int total) {
        int done = 0;
        int fixed = 0;
        try {
            for (Map.Entry<Long, List<Long>> entry : byAssignment.entrySet()) {
                Assignment assignment = assignmentRepo.findById(entry.getKey()).orElse(null);
                if (assignment != null) {
                    // Best effort: without an AI rubric the default one (out of 1, scaled to points) is used.
                    ensureAiRubric(assignment);
                }
                for (Long submissionId : entry.getValue()) {
                    if (assignment != null && regrade(submissionId, assignment, false)) {
                        fixed++;
                    }
                    done++;
                    fixStatus = new FixGradesStatus(true, total, done, fixed, done - fixed);
                }
            }
        } finally {
            fixStatus = new FixGradesStatus(false, total, total, fixed, total - fixed);
            logger.warn("Fix out-of-range grades: {} of {} re-graded", fixed, total);
        }
    }

    private static Map<Long, List<Long>> groupByAssignment(List<Map<String, Object>> rows) {
        Map<Long, List<Long>> byAssignment = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byAssignment.computeIfAbsent(((Number) row.get("assignment_id")).longValue(), id -> new ArrayList<>())
                    .add(((Number) row.get("id")).longValue());
        }
        return byAssignment;
    }

    /** Makes sure the assignment has a usable AI rubric, generating one if needed. */
    private boolean ensureAiRubric(Assignment assignment) {
        if (AssignmentRubricService.isAiRubricReady(assignment.getAiRubric(), assignment.getPoints())) {
            return true;
        }
        String generated = rubricService.generateRubric(
                assignment.getName(), assignment.getDescription(), null, assignment.getPoints());
        if (generated == null) {
            logger.info("AI re-grade: rubric for assignment {} still unavailable; will retry", assignment.getId());
            return false;
        }
        // Update only the rubric column so concurrent edits to the assignment aren't overwritten.
        jdbcTemplate.update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", generated, assignment.getId());
        assignment.setAiRubric(generated);
        logger.info("AI re-grade: generated rubric for assignment {}", assignment.getId());
        return true;
    }

    /**
     * Grades one submission once. {@code flaggedOnly} is the background-job mode: skip it unless it
     * is still flagged, and clear the flag when it turns out not gradeable. Returns true if graded.
     */
    private boolean regrade(Long submissionId, Assignment assignment, boolean flaggedOnly) {
        try {
            AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
            if (submission == null) {
                return false;
            }
            // Loaded outside a session, so swap the lazy proxy for the already-loaded assignment.
            submission.setAssignment(assignment);
            AssignmentAiGradingService.GradeResult result = aiGradingService.grade(submission);
            if ("failed".equals(result.status()) || (!flaggedOnly && !"graded".equals(result.status()))) {
                return false; // Background job: leave flagged so the next run retries.
            }
            // Reload so a teacher's manual grade or a student's edit made while the AI was
            // grading isn't overwritten with a grade for stale content.
            AssignmentSubmission current = submissionRepo.findById(submissionId).orElse(null);
            if (current == null
                    || (flaggedOnly && !Boolean.TRUE.equals(current.getNeedsAiRegrade()))
                    || !Objects.equals(current.getContent(), submission.getContent())) {
                return false;
            }
            if ("graded".equals(result.status())) {
                result.applyTo(current);
            } else {
                current.setNeedsAiRegrade(false);
            }
            submissionRepo.save(current);
            logger.info("AI re-grade: submission {} -> {}", submissionId,
                    "graded".equals(result.status()) ? result.grade() : result.status());
            return "graded".equals(result.status());
        } catch (Exception e) {
            logger.warn("AI re-grade failed for submission {}: {}", submissionId, e.getMessage());
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
