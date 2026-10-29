package com.open.spring.mvc.uibuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.BulkVisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.CreateRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageDetail;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageSummary;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PublishRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RestoreRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.SaveRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.VisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Caller;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Outcome;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Result;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.GalleryItem;

/**
 * The UI Builder queries, transactions and row lock against a real SQLite file, with ddl-auto=none
 * so the tables are the ones UiBuilderPageTableInitializer creates. The people and groups tables
 * are reduced to the columns these queries read. Runs outside a test transaction so each service
 * call commits, and several threads can race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UiBuilderPageService.class, UiBuilderSpecRules.class, UiBuilderPageTableInitializer.class,
        UiBuilderPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UiBuilderPersistenceTest {

    private static final Path DATABASE = createDatabaseFile();

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?journal_mode=WAL");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private static Path createDatabaseFile() {
        try {
            return Files.createTempFile("ui-builder-test", ".db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void deleteDatabase() throws IOException {
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
    }

    /** The JPA test slice has no Jackson, and the course groups are given here rather than bound. */
    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        UiBuilderLimits uiBuilderLimits() {
            return new UiBuilderLimits();
        }

        @Bean
        CourseGroupProperties courseGroupProperties() {
            CourseGroupProperties courses = new CourseGroupProperties();
            courses.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
            courses.setPeriods(Map.of("CSA", "2", "CSP", "3", "CSH", "2", "CSSE", "1"));
            return courses;
        }
    }

    @MockitoBean
    private DirectMessageService directMessages;

    @Autowired private UiBuilderPageService service;
    @Autowired private UiBuilderPageTableInitializer initializer;
    @Autowired private UiBuilderPageJpaRepository pages;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final Caller hop = new Caller(1, "hop", "Hop Student", false);
    private final Caller ada = new Caller(2, "ada", "Ada Student", false);
    private final Caller niko = new Caller(4, "niko", "Niko Teacher", true);

    @BeforeEach
    void setUp() {
        initializer.initialize();
        jdbc.execute("DELETE FROM ui_builder_page");
        jdbc.execute("DELETE FROM ui_builder_page_revision");
        jdbc.execute("DELETE FROM ui_builder_page_editor");
        // Just the columns of the people and groups tables that the UI Builder reads.
        jdbc.execute("CREATE TABLE IF NOT EXISTS submitter (id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS person (id INTEGER PRIMARY KEY, uid varchar(255), name varchar(255),"
                + " email varchar(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"groups\" (id INTEGER PRIMARY KEY, name varchar(255),"
                + " period varchar(255), course varchar(255), grades_json text)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS group_members (group_id bigint, person_id bigint)");
        jdbc.execute("DELETE FROM submitter");
        jdbc.execute("DELETE FROM person");
        jdbc.execute("DELETE FROM \"groups\"");
        jdbc.execute("DELETE FROM group_members");
        for (Object[] person : List.of(new Object[] {1, "hop", "Hop Student"}, new Object[] {2, "ada", "Ada Student"},
                new Object[] {4, "niko", "Niko Teacher"})) {
            jdbc.update("INSERT INTO submitter (id) VALUES (?)", person[0]);
            jdbc.update("INSERT INTO person (id, uid, name, email) VALUES (?, ?, ?, ?)", person[0], person[1], person[2],
                    person[1] + "@example.com");
        }
        jdbc.update("INSERT INTO submitter (id) VALUES (40)");
        jdbc.update("INSERT INTO \"groups\" (id, name) VALUES (40, 'Binary Beasts')");
        jdbc.update("INSERT INTO group_members (group_id, person_id) VALUES (40, 1), (40, 2)");
    }

    private ObjectNode spec(String title) throws IOException {
        return (ObjectNode) json.readTree("""
                {"schemaVersion": 1, "kind": "ocs-ui-page",
                 "page": {"title": "%s", "description": "", "byline": "", "course": "csp", "width": "contained", "image": ""},
                 "sections": [{"id": "s-main0001", "type": "stack", "settings": {}, "hidden": false,
                   "cells": [{"blocks": [{"id": "b-hero0001", "type": "hero", "settings": {"title": "%s"}, "hidden": false}]}]}]}
                """.formatted(title, title));
    }

    private PageDetail create(Caller caller, String title, Integer groupId) throws IOException {
        Result<PageDetail> result = service.create(caller, new CreateRequest(null, spec(title), null, null, null, null,
                groupId == null ? null : IntNode.valueOf(groupId)));
        assertEquals(Outcome.CREATED, result.outcome(), () -> String.valueOf(result.details()));
        return result.body();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void everyQueryAndWriteRunsOnTheInitializedTables() throws IOException {
        PageDetail team = create(hop, "Flood Watch", 40);
        long id = team.summary().id();
        assertEquals("Binary Beasts", team.summary().groupName());
        assertEquals("Hop Student", team.summary().ownerName());
        assertEquals(List.of(2L), jdbc.queryForList("SELECT person_id FROM ui_builder_page_editor", Long.class));
        create(ada, "Ada's page", null);

        assertEquals(List.of("Ada's page", "Flood Watch"),
                service.list(ada, "mine", null).body().stream().map(PageSummary::title).toList());
        assertEquals(2, service.list(niko, "class", "csp").body().size());
        assertEquals(2, service.list(niko, "class", null).body().size());
        assertEquals(List.of("Binary Beasts"), service.myGroups(hop).body().stream()
                .map(UiBuilderPageApiRestController.GroupOption::name).toList());

        PageDetail saved = service.save(ada, id, new SaveRequest(0L, spec("Flood Watch v1"), null, null, null, null,
                null, null)).body();
        assertEquals(1, saved.summary().version());
        assertEquals(Outcome.OK, service.lease(ada, id, false).outcome());
        assertEquals("Ada Student", service.open(hop, id).body().summary().lease().holderName());

        PageDetail published = service.publish(hop, id, new PublishRequest(1L, "flood-watch")).body();
        assertEquals("PUBLISHED", published.summary().status());
        assertFalse(service.slugAvailable("flood-watch").body().available());
        List<GalleryItem> members = service.gallery(ada, null).body();
        assertEquals(List.of("Flood Watch v1"), members.stream().map(GalleryItem::title).toList());
        assertTrue(service.gallery(null, null).body().isEmpty());
        assertTrue(service.published(ada, "flood-watch").body().groups().isEmpty());

        long until = System.currentTimeMillis() + 3_600_000;
        assertEquals(1, service.approveCourse(niko, new BulkVisibilityRequest("csp", until)).body().updated());
        assertEquals(List.of("flood-watch"), service.gallery(null, "csp").body().stream().map(GalleryItem::slug).toList());
        assertEquals("Flood Watch v1", service.published(null, "flood-watch").body().title());
        assertEquals(Outcome.OK, service.setVisibility(hop, id, new VisibilityRequest("SIGNED_IN", null)).outcome());
        assertTrue(service.gallery(null, null).body().isEmpty());

        long first = service.revisions(hop, id).body().get(service.revisions(hop, id).body().size() - 1).revisionId();
        PageDetail restored = service.restore(hop, id, first, new RestoreRequest(1L)).body();
        assertEquals("Flood Watch", restored.summary().title());
        assertEquals(List.of("RESTORE", "PUBLISH", "SAVE", "SAVE"),
                service.revisions(hop, id).body().stream().map(UiBuilderPageApiRestController.RevisionSummary::kind).toList());

        assertEquals(Outcome.NO_CONTENT, service.delete(hop, id).outcome());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ui_builder_page_revision WHERE page_id = ?",
                Integer.class, id));
        assertEquals("flood-watch", jdbc.queryForObject("SELECT slug FROM ui_builder_page WHERE id = ?", String.class, id));
        assertEquals(List.of("Ada's page"), service.list(ada, "mine", null).body().stream().map(PageSummary::title).toList());
    }

    @Test
    void aRefusedCreateLeavesNoRowBehind() throws IOException {
        Result<PageDetail> result = service.create(hop, new CreateRequest(null, spec("Bad group"), null, null, null,
                null, IntNode.valueOf(999)));

        assertEquals(Outcome.INVALID_GROUP, result.outcome());
        assertEquals(0, count("ui_builder_page"));
        assertEquals(0, count("ui_builder_page_revision"));
    }

    @Test
    void twoPagesCanNeverShareASlug() throws IOException {
        long first = create(hop, "One", null).summary().id();
        long second = create(ada, "Two", null).summary().id();
        service.publish(hop, first, new PublishRequest(0L, "same-name"));

        assertEquals(Outcome.SLUG_TAKEN, service.publish(ada, second, new PublishRequest(0L, "same-name")).outcome());
        // Behind the check, the UNIQUE column. On SQLite publishes queue on the write lock, so the check
        // above sees the first slug; MySQL can race past it, and reports a DataIntegrityViolationException.
        UiBuilderPageEntity row = pages.findById(second).orElseThrow();
        row.setSlug("same-name");
        assertThrows(DataAccessException.class, () -> pages.saveAndFlush(row));
        assertNull(jdbc.queryForObject("SELECT slug FROM ui_builder_page WHERE id = ?", String.class, second));
    }

    @Test
    void writersSavingTheSameVersionAtOnceGetOneSaveAndConflictsWithoutSqliteBusy() throws Exception {
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int round = 0; round < 5; round++) {
                long id = create(hop, "Race " + round, 40).summary().id();
                CyclicBarrier start = new CyclicBarrier(writers);
                List<Future<Outcome>> futures = new ArrayList<>();
                for (int writer = 0; writer < writers; writer++) {
                    Caller caller = writer % 2 == 0 ? hop : ada;
                    ObjectNode next = spec("Writer " + writer);
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return service.save(caller, id, new SaveRequest(0L, next, null, null, null, null, null, null)).outcome();
                    }));
                }
                List<Outcome> outcomes = new ArrayList<>();
                for (Future<Outcome> future : futures) {
                    outcomes.add(future.get(30, TimeUnit.SECONDS)); // an SQLITE_BUSY would surface here
                }

                assertEquals(1, outcomes.stream().filter(Outcome.OK::equals).count(), outcomes.toString());
                assertEquals(writers - 1, outcomes.stream().filter(Outcome.VERSION_CONFLICT::equals).count(), outcomes.toString());
                assertEquals(1L, jdbc.queryForObject("SELECT draft_version FROM ui_builder_page WHERE id = ?", Long.class, id));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
