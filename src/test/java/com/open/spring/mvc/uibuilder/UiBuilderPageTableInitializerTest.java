package com.open.spring.mvc.uibuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
class UiBuilderPageTableInitializerTest {

    private static final List<String> PAGE_COLUMNS = List.of(
            "id", "slug", "title", "description", "byline", "course", "image", "owner_person_id", "group_id",
            "status", "visibility", "public_requested", "public_until", "draft_version", "draft_spec",
            "published_version", "published_spec", "public_version", "public_spec", "published_at", "created_at",
            "updated_at", "updated_by_name", "lease_person_id", "lease_name", "lease_expires_at", "deleted_at",
            "published_card", "public_card");
    private static final List<String> REVISION_COLUMNS = List.of(
            "id", "page_id", "page_version", "kind", "spec", "saved_by_person_id", "saved_by_name", "saved_at");
    private static final List<String> EDITOR_COLUMNS = List.of("id", "page_id", "person_id");
    private static final List<String> INDEXES = List.of(
            "ui_builder_page_editor_person", "ui_builder_page_group", "ui_builder_page_owner",
            "ui_builder_page_revision_page", "ui_builder_page_status_published");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private UiBuilderPageTableInitializer initializer;

    @BeforeEach
    void setUp() {
        // One shared connection, so every statement sees the same in-memory database.
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        initializer = new UiBuilderPageTableInitializer(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
    }

    private List<String> indexes() {
        return jdbcTemplate.queryForList("SELECT name FROM sqlite_master WHERE type = 'index'"
                + " AND name LIKE 'ui_builder%' ORDER BY name", String.class);
    }

    @Test
    void createsTheThreeTablesAndTheirIndexes() {
        initializer.initialize();

        assertEquals(PAGE_COLUMNS, columns("ui_builder_page"));
        assertEquals(REVISION_COLUMNS, columns("ui_builder_page_revision"));
        assertEquals(EDITOR_COLUMNS, columns("ui_builder_page_editor"));
        assertEquals(INDEXES, indexes());
    }

    @Test
    void aNewPageGetsTheColumnDefaults() {
        initializer.initialize();
        jdbcTemplate.update("INSERT INTO ui_builder_page (title, owner_person_id, draft_spec, created_at, updated_at)"
                + " VALUES ('Flood Watch', 7, '{}', 1, 1)");

        assertEquals(List.of(Map.of("course", "", "status", "DRAFT", "visibility", "SIGNED_IN",
                "public_requested", 0, "draft_version", 0)),
                jdbcTemplate.queryForList("SELECT course, status, visibility, public_requested, draft_version"
                        + " FROM ui_builder_page"));
    }

    @Test
    void slugsAndEditorsAreUniqueButManyPagesHaveNoSlug() {
        initializer.initialize();
        String insert = "INSERT INTO ui_builder_page (slug, title, owner_person_id, draft_spec, created_at, updated_at)"
                + " VALUES (?, 'Page', 7, '{}', 1, 1)";
        jdbcTemplate.update(insert, (Object) null);
        jdbcTemplate.update(insert, (Object) null);
        jdbcTemplate.update(insert, "flood-watch");

        // Plain JDBC has no SQLite error codes, so the violation arrives uncategorized; through JPA the
        // SQLite dialect turns it into a DataIntegrityViolationException (see UiBuilderPersistenceTest).
        DataAccessException slug = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(insert, "flood-watch"));
        assertTrue(slug.getMessage().contains("UNIQUE"), slug.getMessage());
        jdbcTemplate.update("INSERT INTO ui_builder_page_editor (page_id, person_id) VALUES (1, 8)");
        DataAccessException editor = assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("INSERT INTO ui_builder_page_editor (page_id, person_id) VALUES (1, 8)"));
        assertTrue(editor.getMessage().contains("UNIQUE"), editor.getMessage());
    }

    @Test
    void aTableFromBeforeTheGalleryCardsGetsThemAndKeepsItsRows() {
        String older = String.join(", ", PAGE_COLUMNS.subList(0, PAGE_COLUMNS.indexOf("published_card")).stream()
                .map(column -> column.equals("id") ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : column)
                .toList());
        jdbcTemplate.execute("CREATE TABLE ui_builder_page (" + older + ")");
        jdbcTemplate.update("INSERT INTO ui_builder_page (title, owner_person_id, draft_spec, created_at, updated_at)"
                + " VALUES ('Old page', 7, '{}', 1, 1)");

        initializer.initialize();
        initializer.initialize();

        assertEquals(PAGE_COLUMNS, columns("ui_builder_page"));
        assertEquals(List.of(Map.of("title", "Old page")), jdbcTemplate.queryForList(
                "SELECT title FROM ui_builder_page WHERE published_card IS NULL AND public_card IS NULL"));
    }

    @Test
    void runningAgainChangesNothing() {
        initializer.initialize();
        jdbcTemplate.update("INSERT INTO ui_builder_page (title, owner_person_id, draft_spec, created_at, updated_at)"
                + " VALUES ('Kept', 7, '{}', 1, 1)");

        initializer.initialize();

        assertEquals(PAGE_COLUMNS, columns("ui_builder_page"));
        assertEquals(INDEXES, indexes());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ui_builder_page", Integer.class));
        assertTrue(columns("ui_builder_page_revision").containsAll(REVISION_COLUMNS));
    }
}
