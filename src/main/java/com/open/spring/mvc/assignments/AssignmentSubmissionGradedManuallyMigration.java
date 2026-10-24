package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Adds assignment_submission.graded_manually, which stops AI grading from replacing a grade
 * a teacher or assignment creator set by hand.
 *
 * Hibernate runs with ddl-auto=none, so this follows AssignmentContentUrlMigration: an
 * idempotent ALTER on every boot whose duplicate-column error is swallowed. Existing rows stay
 * NULL, which the entity and the AI-grade UPDATE both treat as "not manually graded".
 */
@Component
@RequiredArgsConstructor
public class AssignmentSubmissionGradedManuallyMigration {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionGradedManuallyMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateAssignmentSubmissionGradedManually() {
        return args -> {
            try {
                jdbcTemplate.execute(
                    "ALTER TABLE \"assignment_submission\" ADD COLUMN \"graded_manually\" boolean");
                logger.info("Added 'graded_manually' column to 'assignment_submission' table");
            } catch (DataAccessException ignored) {
                // Column already exists on every boot after the first.
            }
        };
    }
}
