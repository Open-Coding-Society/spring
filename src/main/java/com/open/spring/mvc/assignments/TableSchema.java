package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent schema steps shared by the grading table initializers, and the column types that
 * differ between SQLite and MySQL. Statements are unquoted so they run on both.
 */
record TableSchema(JdbcTemplate jdbc, boolean mySql) {

    private static final Logger logger = LoggerFactory.getLogger(TableSchema.class);

    static TableSchema of(JdbcTemplate jdbc) {
        String product = jdbc.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        String name = product == null ? "" : product.toLowerCase();
        return new TableSchema(jdbc, name.contains("mysql") || name.contains("mariadb"));
    }

    String idColumn() {
        return mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    String timestamp() {
        return mySql ? "DATETIME(6)" : "TIMESTAMP";
    }

    String decimal() {
        return mySql ? "DOUBLE" : "FLOAT";
    }

    void createTable(String table, String... columns) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (" + String.join(", ", columns) + ")");
    }

    /** Adds each column that is missing; existing ones are left alone. */
    void addColumns(String table, String... columnDefinitions) {
        for (String definition : columnDefinitions) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
                logger.info("Added {} column to {}", definition, table);
            } catch (DataAccessException e) {
                // Already there.
            }
        }
    }

    /** MySQL has no CREATE INDEX IF NOT EXISTS, so an existing index is an expected failure. */
    void createIndex(String name, String table, String column) {
        try {
            jdbc.execute("CREATE INDEX " + name + " ON " + table + " (" + column + ")");
        } catch (DataAccessException e) {
            // Already there.
        }
    }

    /** Changes a varchar column to TEXT on MySQL; SQLite doesn't enforce declared lengths. */
    void widenToText(String table, String column) {
        if (!mySql) {
            return;
        }
        try {
            String type = jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, table, column);
            if (!"text".equalsIgnoreCase(type)) {
                jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " TEXT");
                logger.info("Changed {}.{} from {} to TEXT", table, column, type);
            }
        } catch (DataAccessException e) {
            logger.warn("Could not change {}.{} to TEXT: {}", table, column, e.getMessage());
        }
    }
}
