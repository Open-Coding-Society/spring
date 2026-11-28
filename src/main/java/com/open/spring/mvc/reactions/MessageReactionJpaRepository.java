package com.open.spring.mvc.reactions;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reactions, read by chat or by message. */
public interface MessageReactionJpaRepository extends JpaRepository<MessageReaction, MessageReaction.Key> {

    /** Every reaction in one chat. */
    List<MessageReaction> findByKeyChatTypeAndKeyChatId(ChatType chatType, Long chatId);

    /** Every reaction on one message. */
    List<MessageReaction> findByKeyChatTypeAndKeyChatIdAndKeyMessageId(ChatType chatType, Long chatId, String messageId);

    /**
     * Removes one person's reaction in one statement. Reading the row first and then deleting it
     * let a second remove at the same moment fail; a statement that deletes 0 rows doesn't.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from MessageReaction r where r.key.chatType = :chatType and r.key.chatId = :chatId"
            + " and r.key.messageId = :messageId and r.key.personId = :personId and r.key.reaction = :reaction")
    int deleteOne(@Param("chatType") ChatType chatType, @Param("chatId") Long chatId, @Param("messageId") String messageId,
            @Param("personId") Long personId, @Param("reaction") Reaction reaction);

    /** Removes every reaction on one message in one statement, as when the message is deleted. */
    @Modifying(flushAutomatically = true)
    @Query("delete from MessageReaction r where r.key.chatType = :chatType and r.key.chatId = :chatId"
            + " and r.key.messageId = :messageId")
    int deleteAllOnMessage(@Param("chatType") ChatType chatType, @Param("chatId") Long chatId,
            @Param("messageId") String messageId);

    /**
     * The uid and name of each person who reacted, in one query. Loading whole Person rows
     * would also load each one's eager links, several queries per person.
     */
    @Query("select p.id as id, p.uid as uid, p.name as name from Person p where p.id in :ids")
    List<ReactorName> findReactorNames(@Param("ids") Collection<Long> ids);

    /** A person who reacted, as far as a reaction needs to know them. */
    interface ReactorName {
        Long getId();

        String getUid();

        String getName();
    }
}
