package com.open.spring.mvc.uibuilder;

import java.util.Arrays;
import java.util.List;

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
 * Creates the three UI Builder tables and their indexes at startup, because Hibernate runs with
 * ddl-auto=none. Every statement is safe to run again: CREATE TABLE IF NOT EXISTS, and each index
 * in its own try, since MySQL has no CREATE INDEX IF NOT EXISTS. There are no foreign keys (the
 * entities hold plain ids, like the rest of this app's newer tables). Column names are the ones
 * Hibernate maps the entities' fields to.
 */
@Component
@RequiredArgsConstructor
public class UiBuilderPageTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(UiBuilderPageTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateUiBuilderPages() {
        return args -> initialize();
    }

    void initialize() {
        Dialect dialect;
        try {
            dialect = jdbcTemplate.execute((ConnectionCallback<Dialect>) connection -> new Dialect(
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getIdentifierQuoteString()));
        } catch (DataAccessException e) {
            logger.warn("Could not read the database type for the UI Builder tables: {}", e.getMessage());
            return;
        }

        create(dialect, "ui_builder_page", List.of(
                dialect.idColumn(),
                dialect.quote("slug") + " varchar(64) UNIQUE",
                dialect.quote("title") + " varchar(120) NOT NULL",
                dialect.quote("description") + " varchar(300)",
                dialect.quote("byline") + " varchar(120)",
                dialect.quote("course") + " varchar(16) NOT NULL DEFAULT ''",
                dialect.quote("image") + " varchar(500)",
                dialect.quote("owner_person_id") + " bigint NOT NULL",
                dialect.quote("group_id") + " bigint",
                dialect.quote("status") + " varchar(16) NOT NULL DEFAULT 'DRAFT'",
                dialect.quote("visibility") + " varchar(16) NOT NULL DEFAULT 'SIGNED_IN'",
                dialect.quote("public_requested") + " boolean NOT NULL DEFAULT FALSE",
                dialect.quote("public_until") + " bigint",
                dialect.quote("draft_version") + " bigint NOT NULL DEFAULT 0",
                dialect.quote("draft_spec") + " mediumtext NOT NULL",
                dialect.quote("published_version") + " bigint",
                dialect.quote("published_spec") + " mediumtext",
                dialect.quote("public_version") + " bigint",
                dialect.quote("public_spec") + " mediumtext",
                dialect.quote("published_at") + " bigint",
                dialect.quote("created_at") + " bigint NOT NULL",
                dialect.quote("updated_at") + " bigint NOT NULL",
                dialect.quote("updated_by_name") + " varchar(255)",
                dialect.quote("lease_person_id") + " bigint",
                dialect.quote("lease_name") + " varchar(255)",
                dialect.quote("lease_expires_at") + " bigint",
                dialect.quote("deleted_at") + " bigint"));

        create(dialect, "ui_builder_page_revision", List.of(
                dialect.idColumn(),
                dialect.quote("page_id") + " bigint NOT NULL",
                dialect.quote("page_version") + " bigint NOT NULL",
                dialect.quote("kind") + " varchar(16) NOT NULL",
                dialect.quote("spec") + " mediumtext NOT NULL",
                dialect.quote("saved_by_person_id") + " bigint",
                dialect.quote("saved_by_name") + " varchar(255)",
                dialect.quote("saved_at") + " bigint NOT NULL"));

        create(dialect, "ui_builder_page_editor", List.of(
                dialect.idColumn(),
                dialect.quote("page_id") + " bigint NOT NULL",
                dialect.quote("person_id") + " bigint NOT NULL",
                "UNIQUE (" + dialect.quote("page_id") + ", " + dialect.quote("person_id") + ")"));

        index(dialect, "ui_builder_page_owner", "ui_builder_page", "owner_person_id");
        index(dialect, "ui_builder_page_group", "ui_builder_page", "group_id");
        index(dialect, "ui_builder_page_status_published", "ui_builder_page", "status", "published_at");
        index(dialect, "ui_builder_page_revision_page", "ui_builder_page_revision", "page_id", "id");
        index(dialect, "ui_builder_page_editor_person", "ui_builder_page_editor", "person_id");
    }

    private void create(Dialect dialect, String table, List<String> columns) {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + dialect.quote(table) + " (" + String.join(", ", columns) + ")");
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", table, e.getMessage());
        }
    }

    private void index(Dialect dialect, String name, String table, String... columns) {
        List<String> quoted = Arrays.stream(columns).map(dialect::quote).toList();
        try {
            jdbcTemplate.execute("CREATE INDEX " + dialect.ifNotExists() + dialect.quote(name) + " ON "
                    + dialect.quote(table) + " (" + String.join(", ", quoted) + ")");
        } catch (DataAccessException e) {
            // Already there (MySQL reports a duplicate key name).
        }
    }

    /** What differs between SQLite and MySQL here: identifier quotes, the auto-increment id and IF NOT EXISTS on indexes. */
    private record Dialect(String productName, String quoteString) {
        boolean mySql() {
            String product = productName == null ? "" : productName.toLowerCase();
            return product.contains("mysql") || product.contains("mariadb");
        }

        String quote(String identifier) {
            if (quoteString == null || quoteString.isBlank()) return identifier;
            return quoteString + identifier + quoteString;
        }

        String idColumn() {
            return mySql()
                    ? quote("id") + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                    : quote("id") + " INTEGER PRIMARY KEY AUTOINCREMENT";
        }

        String ifNotExists() {
            return mySql() ? "" : "IF NOT EXISTS ";
        }
    }
}
