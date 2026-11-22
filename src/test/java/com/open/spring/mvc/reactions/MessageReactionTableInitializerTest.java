package com.open.spring.mvc.reactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** The statement that creates message_reaction at startup, run on SQLite as a local server does. */
class MessageReactionTableInitializerTest {

    private static final String INSERT = "INSERT INTO message_reaction "
            + "(chat_type, chat_id, message_id, person_id, reaction) VALUES (?, ?, ?, ?, ?)";

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
    void createsTheTableAndCanRunTwice() {
        jdbc.execute(MessageReactionTableInitializer.CREATE_TABLE);
        jdbc.execute(MessageReactionTableInitializer.CREATE_TABLE);

        jdbc.update(INSERT, "DM", 7L, "42", 1L, "LIKE");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message_reaction", Integer.class));
    }

    @Test
    void rejectsTheSameReactionTwice() {
        jdbc.execute(MessageReactionTableInitializer.CREATE_TABLE);
        jdbc.update(INSERT, "DM", 7L, "42", 1L, "LIKE");

        assertThrows(DataAccessException.class, () -> jdbc.update(INSERT, "DM", 7L, "42", 1L, "LIKE"));
        jdbc.update(INSERT, "DM", 7L, "42", 1L, "LOVE");

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM message_reaction", Integer.class));
    }
}
