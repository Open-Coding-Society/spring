package com.open.spring.mvc.sprintretro;

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
 * Initializes the sprint_retro table for databases managed with ddl-auto=none.
 *
 * It is a fallback: an existing table is never changed. The columns match the schema
 * Hibernate generates for SprintRetroEntity; the identity and timestamp types differ between
 * SQLite and MySQL, so they are chosen from the database product name.
 */
@Component
@RequiredArgsConstructor
public class SprintRetroTableInitializer {

    static final String TABLE = "sprint_retro";

    private static final Logger logger = LoggerFactory.getLogger(SprintRetroTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateSprintRetro() {
        return args -> initialize();
    }

    void initialize() {
        try {
            boolean mySql = isMySql(jdbcTemplate);
            String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
            String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
            // Unquoted names so the statements run on SQLite and MySQL.
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "name VARCHAR(100) NOT NULL, "
                    + "start_date DATE NOT NULL, "
                    + "end_date DATE, "
                    + "created_by BIGINT, "
                    + "created_at " + timestamp + " NOT NULL)");
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }

    static boolean isMySql(JdbcTemplate jdbcTemplate) {
        String product = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        String name = product == null ? "" : product.toLowerCase();
        return name.contains("mysql") || name.contains("mariadb");
    }
}
