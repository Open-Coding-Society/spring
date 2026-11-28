package com.open.spring.mvc.reactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Creates message_reaction for databases run with ddl-auto=none, and leaves an existing one
 * as it is. The primary key is all five columns: that keeps a person to one of each reaction
 * on a message, and it needs no auto-increment, so one statement in unquoted lowercase with
 * plain types runs on both SQLite and MySQL.
 */
@Component
@RequiredArgsConstructor
public class MessageReactionTableInitializer {

    static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS message_reaction ("
            + "chat_type varchar(8) NOT NULL, "
            + "chat_id bigint NOT NULL, "
            + "message_id varchar(64) NOT NULL, "
            + "person_id bigint NOT NULL, "
            + "reaction varchar(16) NOT NULL, "
            + "PRIMARY KEY (chat_type, chat_id, message_id, person_id, reaction))";

    private static final Logger logger = LoggerFactory.getLogger(MessageReactionTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateMessageReaction() {
        return args -> {
            try {
                jdbcTemplate.execute(CREATE_TABLE);
            } catch (DataAccessException e) {
                // The rest of Spring keeps running, as it does when another table can't be made.
                // Until the table exists the reaction routes fail, and deleting a message still
                // works but logs that its reactions couldn't be removed.
                logger.error("Could not create the message_reaction table, so reactions won't work: {}", e.getMessage());
            }
        };
    }
}
