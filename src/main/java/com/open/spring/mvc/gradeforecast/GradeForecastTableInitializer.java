package com.open.spring.mvc.gradeforecast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Initializes the grade_forecast table for databases managed with ddl-auto=none.
 *
 * It is a fallback: an existing table is never changed. The columns match the schema
 * Hibernate generates for GradeForecastEntity; the identity, timestamp and decimal types
 * differ between SQLite and MySQL, so they are chosen from the database product name.
 */
@Component
@RequiredArgsConstructor
public class GradeForecastTableInitializer {

    static final String TABLE = "grade_forecast";

    private static final Logger logger = LoggerFactory.getLogger(GradeForecastTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateGradeForecast() {
        return args -> initialize();
    }

    void initialize() {
        try {
            String product = jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            String name = product == null ? "" : product.toLowerCase();
            boolean mySql = name.contains("mysql") || name.contains("mariadb");
            String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
            String decimal = mySql ? "DOUBLE" : "FLOAT";
            String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
            // Unquoted names so the statements run on SQLite and MySQL.
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "person_id BIGINT NOT NULL, "
                    + "attendance INTEGER, "
                    + "work_habits INTEGER, "
                    + "behavior INTEGER, "
                    + "timeliness INTEGER, "
                    + "tech_sense INTEGER, "
                    + "tech_talk INTEGER, "
                    + "tech_growth INTEGER, "
                    + "advocacy INTEGER, "
                    + "communication INTEGER, "
                    + "integrity INTEGER, "
                    + "organization INTEGER, "
                    + "model_grade " + decimal + " NOT NULL, "
                    + "student_guess " + decimal + ", "
                    + "current_grade " + decimal + ", "
                    + "created_at " + timestamp + " NOT NULL)");
            try {
                // MySQL has no CREATE INDEX IF NOT EXISTS, so an existing index is an expected failure.
                jdbcTemplate.execute("CREATE INDEX idx_grade_forecast_person ON " + TABLE + " (person_id, created_at)");
            } catch (DataAccessException e) {
                // Already there.
            }
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }
}
