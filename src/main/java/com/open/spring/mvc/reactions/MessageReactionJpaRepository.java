package com.open.spring.mvc.reactions;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reactions, read by chat or by message. */
public interface MessageReactionJpaRepository extends JpaRepository<MessageReaction, MessageReaction.Key> {

    /** Every reaction in one chat. */
    List<MessageReaction> findByKeyChatTypeAndKeyChatId(ChatType chatType, Long chatId);

    /** Every reaction on one message. */
    List<MessageReaction> findByKeyChatTypeAndKeyChatIdAndKeyMessageId(ChatType chatType, Long chatId, String messageId);

    /** Removes every reaction on one message, as when the message is deleted. */
    void deleteByKeyChatTypeAndKeyChatIdAndKeyMessageId(ChatType chatType, Long chatId, String messageId);
}
