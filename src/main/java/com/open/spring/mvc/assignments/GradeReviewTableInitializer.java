package com.open.spring.mvc.assignments;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Initializes the assignment_grade_review table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class GradeReviewTableInitializer {

    static final String TABLE = "assignment_grade_review";

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migrateGradeReview() {
        return args -> initialize();
    }

    void initialize() {
        TableSchema schema = TableSchema.of(jdbcTemplate);
        schema.createTable(TABLE,
                schema.idColumn(),
                "submission_id BIGINT NOT NULL",
                "assignment_id BIGINT NOT NULL",
                "reason VARCHAR(30) NOT NULL",
                "status VARCHAR(10) NOT NULL",
                "grade_at_open " + schema.decimal(),
                "petition_message TEXT",
                "evidence TEXT",
                "ai_grade " + schema.decimal(),
                "ai_feedback TEXT",
                "similar_submissions TEXT",
                "decision VARCHAR(10)",
                "rationale TEXT",
                "adjusted_grade " + schema.decimal(),
                "adjusted_feedback TEXT",
                "opened_by_id BIGINT NOT NULL",
                "opened_at " + schema.timestamp() + " NOT NULL",
                "decided_by_id BIGINT",
                "decided_at " + schema.timestamp());
        // Tables created before students could petition lack this column.
        schema.addColumns(TABLE, "petition_message TEXT");
        schema.createIndex("idx_grade_review_submission", TABLE, "submission_id");
    }
}
