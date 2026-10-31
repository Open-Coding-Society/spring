package com.open.spring.mvc.sprintretro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Initializes the sprint_reflection table for databases managed with ddl-auto=none.
 *
 * It is a fallback: an existing table is never changed. The columns match the schema
 * Hibernate generates for SprintReflectionEntity, including its one-row-per-student-per-sprint
 * unique key. The JSON columns are TEXT on both SQLite and MySQL.
 */
@Component
@RequiredArgsConstructor
public class SprintReflectionTableInitializer {

    static final String TABLE = "sprint_reflection";

    private static final Logger logger = LoggerFactory.getLogger(SprintReflectionTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateSprintReflection() {
        return args -> initialize();
    }

    void initialize() {
        try {
            boolean mySql = SprintRetroTableInitializer.isMySql(jdbcTemplate);
            String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
            String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
            // Unquoted names so the statements run on SQLite and MySQL.
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "sprint_id BIGINT NOT NULL, "
                    + "person_id BIGINT NOT NULL, "
                    + "student_data TEXT, "
                    + "updated_at " + timestamp + ", "
                    + "teacher_data TEXT, "
                    + "teacher_updated_at " + timestamp + ", "
                    + "teacher_id BIGINT, "
                    + "CONSTRAINT uk_sprint_reflection_sprint_person UNIQUE (sprint_id, person_id))");
            try {
                // MySQL has no CREATE INDEX IF NOT EXISTS, so an existing index is an expected failure.
                jdbcTemplate.execute("CREATE INDEX idx_sprint_reflection_person ON " + TABLE + " (person_id)");
            } catch (DataAccessException e) {
                // Already there.
            }
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }
}
