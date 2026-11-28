package com.open.spring.mvc.reactions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

    // Startup goes on, as it does for the other tables (calls, calendar), but the log says plainly
    // that reactions are broken: a warning was easy to miss.
    @Test
    void aFailedCreateIsLoggedAsAnErrorAndStartupGoesOn() {
        JdbcTemplate failing = new JdbcTemplate(dataSource) {
            @Override
            public void execute(String sql) {
                throw new DataAccessResourceFailureException("the database is read-only");
            }
        };
        ApplicationRunner runner = new MessageReactionTableInitializer(failing).migrateMessageReaction();
        Logger logger = (Logger) LoggerFactory.getLogger(MessageReactionTableInitializer.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
        try {
            assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
            logs.stop();
        }

        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("reactions won't work"), event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("the database is read-only"), event.getFormattedMessage());
    }
}
