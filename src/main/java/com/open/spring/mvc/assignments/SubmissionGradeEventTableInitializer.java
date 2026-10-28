package com.open.spring.mvc.assignments;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Initializes the assignment_submission_grade_event table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class SubmissionGradeEventTableInitializer {

    static final String TABLE = "assignment_submission_grade_event";

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migrateSubmissionGradeEvent() {
        return args -> initialize();
    }

    void initialize() {
        TableSchema schema = TableSchema.of(jdbcTemplate);
        schema.createTable(TABLE,
                schema.idColumn(),
                "submission_id BIGINT NOT NULL",
                "revision INTEGER NOT NULL",
                "event_type VARCHAR(40) NOT NULL",
                "grade " + schema.decimal(),
                "feedback TEXT",
                "effective BOOLEAN",
                "actor_id BIGINT",
                "review_id BIGINT",
                "content TEXT",
                "created_at " + schema.timestamp() + " NOT NULL");
        // Tables created before reviews existed lack this column.
        schema.addColumns(TABLE, "review_id BIGINT");
        schema.createIndex("idx_grade_event_submission", TABLE, "submission_id");
    }
}
