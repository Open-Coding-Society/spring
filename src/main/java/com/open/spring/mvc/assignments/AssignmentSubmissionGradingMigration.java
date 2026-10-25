package com.open.spring.mvc.assignments;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Schema for keeping AI grades, manual overrides and submission revisions apart (see
 * {@link AssignmentSubmission}) and for the grading history table ({@link SubmissionGradeEvent}).
 *
 * Hibernate runs with ddl-auto=none, so like the other migrations here every step is idempotent
 * SQL that runs on each boot. Identifiers are left unquoted (none are reserved words) so the same
 * statements work on SQLite and on MySQL without ANSI_QUOTES.
 *
 * Existing rows are classified once, while manual_override is still NULL:
 * <ul>
 *   <li>a row the previous build flagged graded_manually is a manual override;</li>
 *   <li>a graded row whose feedback isn't the AI's feedback (or that has no AI feedback at all)
 *       was graded by a person, so it becomes a manual override and the AI can't replace it;</li>
 *   <li>any other graded row was graded by the AI: its grade becomes ai_grade, and its feedback is
 *       restored to the AI's full text, which the old 255-character column had cut short.</li>
 * </ul>
 * When unsure, a row is treated as manual, because wrongly protecting an AI grade is harmless
 * while letting the AI overwrite a teacher's grade is not.
 */
@Component
@RequiredArgsConstructor
public class AssignmentSubmissionGradingMigration {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionGradingMigration.class);

    // The old feedback column held at most 255 characters: 254 of the AI text plus an ellipsis.
    private static final int LEGACY_FEEDBACK_PREFIX = 254;

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migrateSubmissionGrading() {
        return args -> migrate();
    }

    void migrate() {
        boolean mysql = isMySql();
        for (String column : List.of("revision INTEGER", "ai_grade FLOAT", "manual_override BOOLEAN",
                "manual_grade FLOAT", "manual_feedback TEXT")) {
            addColumn(column);
        }
        widenFeedbackColumn(mysql);
        backfill();
        createEventTable(mysql);
    }

    private void addColumn(String columnDefinition) {
        try {
            jdbcTemplate.execute("ALTER TABLE assignment_submission ADD COLUMN " + columnDefinition);
            logger.info("Added {} column to assignment_submission", columnDefinition);
        } catch (DataAccessException e) {
            // Column already exists on every boot after the first.
        }
    }

    /**
     * Feedback is stored in full, so the column must be TEXT. MySQL enforces the old varchar(255)
     * and needs the change; SQLite never enforces a declared length, so it has nothing to change.
     */
    private void widenFeedbackColumn(boolean mysql) {
        if (!mysql) {
            return;
        }
        try {
            String type = jdbcTemplate.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assignment_submission' "
                    + "AND COLUMN_NAME = 'feedback'", String.class);
            if (!"text".equalsIgnoreCase(type)) {
                jdbcTemplate.execute("ALTER TABLE assignment_submission MODIFY COLUMN feedback TEXT");
                logger.info("Changed assignment_submission.feedback from {} to TEXT", type);
            }
        } catch (DataAccessException e) {
            logger.warn("Could not change assignment_submission.feedback to TEXT: {}", e.getMessage());
        }
    }

    private void backfill() {
        jdbcTemplate.update("UPDATE assignment_submission SET revision = 1 WHERE revision IS NULL");
        try {
            jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 1, manual_grade = grade, "
                    + "manual_feedback = feedback WHERE manual_override IS NULL AND graded_manually = 1");
        } catch (DataAccessException e) {
            // graded_manually only exists on databases that ran the previous build.
        }
        int manual = jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 1, manual_grade = grade, "
                + "manual_feedback = feedback WHERE manual_override IS NULL AND grade IS NOT NULL "
                + "AND (ai_summary IS NULL OR feedback IS NULL OR SUBSTR(feedback, 1, ?) <> SUBSTR(ai_summary, 1, ?))",
                LEGACY_FEEDBACK_PREFIX, LEGACY_FEEDBACK_PREFIX);
        int ai = jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 0, ai_grade = grade, "
                + "feedback = ai_summary WHERE manual_override IS NULL AND grade IS NOT NULL");
        jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 0 WHERE manual_override IS NULL");
        if (manual + ai > 0) {
            logger.info("Classified existing grades: {} manual, {} AI", manual, ai);
        }
    }

    private void createEventTable(boolean mysql) {
        String id = mysql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS assignment_submission_grade_event ("
                + id + ", "
                + "submission_id BIGINT NOT NULL, "
                + "revision INTEGER NOT NULL, "
                + "event_type VARCHAR(40) NOT NULL, "
                + "grade " + (mysql ? "DOUBLE" : "FLOAT") + ", "
                + "feedback TEXT, "
                + "effective BOOLEAN, "
                + "actor_id BIGINT, "
                + "content TEXT, "
                + "created_at " + (mysql ? "DATETIME(6)" : "TIMESTAMP") + " NOT NULL)");
        try {
            jdbcTemplate.execute("CREATE INDEX idx_grade_event_submission "
                    + "ON assignment_submission_grade_event (submission_id)");
        } catch (DataAccessException e) {
            // Index already exists.
        }
    }

    private boolean isMySql() {
        String product = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        return product != null && product.toLowerCase().contains("mysql");
    }
}
