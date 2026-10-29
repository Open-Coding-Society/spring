package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class TableSchemaTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void detectsSqliteAndUsesItsTypes() {
        TableSchema schema = TableSchema.of(jdbc);

        assertFalse(schema.mySql());
        assertEquals("id INTEGER PRIMARY KEY AUTOINCREMENT", schema.idColumn());
        assertEquals("TIMESTAMP", schema.timestamp());
        assertEquals("FLOAT", schema.decimal());
    }

    @Test
    void usesMySqlTypesOnMySql() {
        TableSchema schema = new TableSchema(jdbc, true);

        assertEquals("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY", schema.idColumn());
        assertEquals("DATETIME(6)", schema.timestamp());
        assertEquals("DOUBLE", schema.decimal());
    }

    @Test
    void everyStepCanRunTwice() {
        TableSchema schema = TableSchema.of(jdbc);
        for (int run = 0; run < 2; run++) {
            schema.createTable("sample", schema.idColumn(), "name TEXT");
            schema.addColumns("sample", "note TEXT", "score FLOAT");
            schema.createIndex("idx_sample_name", "sample", "name");
            schema.widenToText("sample", "name");
        }

        assertEquals(List.of("id", "name", "note", "score"),
                jdbc.queryForList("SELECT name FROM pragma_table_info('sample')", String.class));
    }

    @Test
    void widenToTextOnlyTouchesMySql() {
        JdbcTemplate untouched = mock(JdbcTemplate.class);

        new TableSchema(untouched, false).widenToText("assignment_submission", "feedback");

        verifyNoInteractions(untouched);
    }
}
