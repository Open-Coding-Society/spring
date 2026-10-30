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
class SubmissionGradeEventTableInitializerTest {

    private static final List<String> ALL_COLUMNS = List.of(
            "id", "submission_id", "revision", "event_type", "grade", "feedback", "effective", "actor_id",
            "review_id", "content", "created_at");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private SubmissionGradeEventTableInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        initializer = new SubmissionGradeEventTableInitializer(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private List<String> columns() {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('assignment_submission_grade_event')", String.class);
    }

    @Test
    void createsTheWholeTableAndNumbersEventsInOrder() {
        initializer.initialize();

        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, grade, "
                + "created_at) VALUES (1, 1, 'AI_GRADED', 0.5, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, "
                + "created_at) VALUES (1, 2, 'REVISED', CURRENT_TIMESTAMP)");

        assertEquals(ALL_COLUMNS, columns());
        assertEquals(2, jdbc.queryForObject("SELECT MAX(id) FROM assignment_submission_grade_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                + "AND name = 'idx_grade_event_submission'", Integer.class));
    }

    @Test
    void addsTheReviewColumnToATableFromBeforeReviewsAndKeepsItsRows() {
        jdbc.execute("CREATE TABLE assignment_submission_grade_event (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "submission_id BIGINT NOT NULL, revision INTEGER NOT NULL, event_type VARCHAR(40) NOT NULL, "
                + "grade FLOAT, feedback TEXT, effective BOOLEAN, actor_id BIGINT, content TEXT, "
                + "created_at TIMESTAMP NOT NULL)");
        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, created_at) "
                + "VALUES (1, 1, 'MANUAL_OVERRIDE_RELEASED_BY_REVISION', CURRENT_TIMESTAMP)");

        initializer.initialize();

        assertTrue(columns().contains("review_id"));
        assertEquals("MANUAL_OVERRIDE_RELEASED_BY_REVISION",
                jdbc.queryForObject("SELECT event_type FROM assignment_submission_grade_event", String.class));
    }

    @Test
    void runningAgainChangesNothing() {
        initializer.initialize();
        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, created_at) "
                + "VALUES (1, 1, 'SUBMITTED', CURRENT_TIMESTAMP)");

        initializer.initialize();

        assertEquals(ALL_COLUMNS, columns());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_submission_grade_event", Integer.class));
    }
}
