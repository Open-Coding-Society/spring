package com.open.spring.mvc.reactions;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One person's reaction to one message. The whole row is its key, so a person leaves each
 * reaction at most once on a message. DM messages are rows and group messages live in S3,
 * so a message is named by its chat and its id as text.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "message_reaction")
public class MessageReaction {

    @EmbeddedId
    private Key key;

    /** Which chat, which message, who, and which emoji. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Embeddable
    public static class Key implements Serializable {

        @Enumerated(EnumType.STRING)
        @Column(name = "chat_type", length = 8)
        private ChatType chatType;

        /** The DM conversation's id or the group's id. */
        @Column(name = "chat_id")
        private Long chatId;

        /** A DM message's numeric id as text, or a group message's UUID. */
        @Column(name = "message_id", length = 64)
        private String messageId;

        @Column(name = "person_id")
        private Long personId;

        @Enumerated(EnumType.STRING)
        @Column(name = "reaction", length = 16)
        private Reaction reaction;
    }
}
