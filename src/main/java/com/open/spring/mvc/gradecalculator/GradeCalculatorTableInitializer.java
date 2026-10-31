package com.open.spring.mvc.gradecalculator;

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
 * Initializes the grade_calculator table for databases managed with ddl-auto=none.
 *
 * It is a fallback: an existing table is never changed. The columns match the schema
 * Hibernate generates for GradeCalculatorEntity; the identity and timestamp types differ
 * between SQLite and MySQL, so they are chosen from the database product name.
 */
@Component
@RequiredArgsConstructor
public class GradeCalculatorTableInitializer {

    static final String TABLE = "grade_calculator";

    private static final Logger logger = LoggerFactory.getLogger(GradeCalculatorTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateGradeCalculator() {
        return args -> initialize();
    }

    void initialize() {
        try {
            String product = jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            String name = product == null ? "" : product.toLowerCase();
            boolean mySql = name.contains("mysql") || name.contains("mariadb");
            String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
            String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
            // Unquoted names so the statement runs on SQLite and MySQL.
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "person_id BIGINT NOT NULL UNIQUE, "
                    + "data TEXT NOT NULL, "
                    + "updated_at " + timestamp + " NOT NULL)");
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }
}
