package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
class GradeReviewTableInitializerTest {

    private static final List<String> ALL_COLUMNS = List.of(
            "id", "submission_id", "assignment_id", "reason", "status", "grade_at_open", "petition_message",
            "evidence", "ai_grade", "ai_feedback", "similar_submissions", "decision", "rationale", "adjusted_grade",
            "adjusted_feedback", "opened_by_id", "opened_at", "decided_by_id", "decided_at");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private GradeReviewTableInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        initializer = new GradeReviewTableInitializer(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private List<String> columns() {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('assignment_grade_review')", String.class);
    }

    @Test
    void createsTheWholeTableAndItsIndexesOnAnEmptyDatabase() {
        initializer.initialize();

        assertEquals(ALL_COLUMNS, columns());
        assertEquals(List.of("idx_grade_review_assignment", "idx_grade_review_submission"),
                jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'index' "
                        + "AND tbl_name = 'assignment_grade_review' ORDER BY name", String.class));
        assertEquals(List.of("assignment_id", "status"), jdbc.queryForList(
                "SELECT name FROM pragma_index_info('idx_grade_review_assignment')", String.class));
    }

    @Test
    void addsThePetitionColumnToATableFromBeforePetitionsAndKeepsItsRows() {
        jdbc.execute("CREATE TABLE assignment_grade_review (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "submission_id BIGINT NOT NULL, assignment_id BIGINT NOT NULL, reason VARCHAR(30) NOT NULL, "
                + "status VARCHAR(10) NOT NULL, opened_by_id BIGINT NOT NULL, opened_at TIMESTAMP NOT NULL)");
        jdbc.update("INSERT INTO assignment_grade_review (submission_id, assignment_id, reason, status, opened_by_id, "
                + "opened_at) VALUES (1, 2, 'PETITION', 'OPEN', 3, CURRENT_TIMESTAMP)");

        initializer.initialize();

        assertTrue(columns().contains("petition_message"));
        assertEquals("PETITION", jdbc.queryForObject("SELECT reason FROM assignment_grade_review", String.class));
    }

    @Test
    void runningAgainChangesNothing() {
        initializer.initialize();
        jdbc.update("INSERT INTO assignment_grade_review (submission_id, assignment_id, reason, status, opened_by_id, "
                + "opened_at, petition_message) VALUES (1, 2, 'PETITION', 'OPEN', 5, CURRENT_TIMESTAMP, 'v2 adds part 2')");

        initializer.initialize();

        assertEquals(ALL_COLUMNS, columns());
        assertEquals("v2 adds part 2", jdbc.queryForObject("SELECT petition_message FROM assignment_grade_review", String.class));
    }
}
