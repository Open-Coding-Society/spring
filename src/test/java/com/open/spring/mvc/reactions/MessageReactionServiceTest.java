package com.open.spring.mvc.reactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.reactions.MessageReactionService.ReactionSummary;
import com.open.spring.mvc.reactions.MessageReactionService.Reactor;

/**
 * Saving a reaction once, removing it safely, and the summaries both chats send: reactions in
 * the enum's order, people by name, and nobody listed who no longer has an account.
 */
class MessageReactionServiceTest {

    private MessageReactionJpaRepository repository;
    private MessageReactionService service;
    // "ada" is lowercase on purpose: it sorts before "Ana" only when case is ignored.
    private Person ada;
    private Person ana;
    private Person ben;

    @BeforeEach
    void setUp() {
        repository = mock(MessageReactionJpaRepository.class);
        service = new MessageReactionService(repository);
        ada = person(1L, "ada", "ada");
        ana = person(2L, "ana", "Ana");
        ben = person(3L, "ben", "Ben");
    }

    @Test
    void addSavesANewReactionOnce() {
        MessageReaction.Key key = new MessageReaction.Key(ChatType.DM, 7L, "42", 1L, Reaction.LIKE);
        when(repository.existsById(key)).thenReturn(true);
        service.add(ChatType.DM, 7L, "42", ada, Reaction.LIKE);
        verify(repository, never()).save(any());

        when(repository.existsById(key)).thenReturn(false);
        service.add(ChatType.DM, 7L, "42", ada, Reaction.LIKE);
        verify(repository).save(new MessageReaction(key));
    }

    @Test
    void removeIsOneDeleteStatement() {
        // Read-then-delete let two removes at once fail the second; one statement just deletes 0 rows.
        service.remove(ChatType.DM, 7L, "42", ada, Reaction.LIKE);

        verify(repository).deleteOne(ChatType.DM, 7L, "42", 1L, Reaction.LIKE);
        verify(repository, never()).existsById(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void summariesFollowTheTableOrderAndSortPeople() {
        when(repository.findByKeyChatTypeAndKeyChatIdAndKeyMessageId(ChatType.DM, 7L, "42")).thenReturn(List.of(
                row(ChatType.DM, 7L, "42", 3L, Reaction.WOW),
                row(ChatType.DM, 7L, "42", 2L, Reaction.LIKE),
                row(ChatType.DM, 7L, "42", 1L, Reaction.LIKE)));
        when(repository.findReactorNames(any())).thenReturn(List.of(name(ben), name(ana), name(ada)));

        List<ReactionSummary> summaries = service.forMessage(ChatType.DM, 7L, "42");

        assertEquals(List.of(
                new ReactionSummary("like", List.of(new Reactor("ada", "ada"), new Reactor("ana", "Ana"))),
                new ReactionSummary("wow", List.of(new Reactor("ben", "Ben")))), summaries);
    }

    @Test
    void aPersonWhoIsGoneIsLeftOut() {
        when(repository.findByKeyChatTypeAndKeyChatIdAndKeyMessageId(ChatType.DM, 7L, "42")).thenReturn(List.of(
                row(ChatType.DM, 7L, "42", 1L, Reaction.LIKE),
                row(ChatType.DM, 7L, "42", 2L, Reaction.LIKE),
                row(ChatType.DM, 7L, "42", 2L, Reaction.SAD)));
        // Person 2's account is gone, so their Like goes and their Sad, now nobody's, goes too.
        when(repository.findReactorNames(any())).thenReturn(List.of(name(ada)));

        assertEquals(List.of(new ReactionSummary("like", List.of(new Reactor("ada", "ada")))),
                service.forMessage(ChatType.DM, 7L, "42"));
    }

    @Test
    void forChatKeysByMessageId() {
        when(repository.findByKeyChatTypeAndKeyChatId(ChatType.GROUP, 3L)).thenReturn(List.of(
                row(ChatType.GROUP, 3L, "42", 1L, Reaction.LIKE),
                row(ChatType.GROUP, 3L, "57", 3L, Reaction.HAHA),
                row(ChatType.GROUP, 3L, "60", 9L, Reaction.LIKE)));
        when(repository.findReactorNames(any())).thenReturn(List.of(name(ada), name(ben)));

        Map<String, List<ReactionSummary>> byMessage = service.forChat(ChatType.GROUP, 3L);

        // "60" is left out: its only reaction is from someone whose account is gone.
        assertEquals(Map.of(
                "42", List.of(new ReactionSummary("like", List.of(new Reactor("ada", "ada")))),
                "57", List.of(new ReactionSummary("haha", List.of(new Reactor("ben", "Ben"))))), byMessage);
    }

    @Test
    void removeAllForDeletesThatMessagesReactionsInOneStatement() {
        service.removeAllFor(ChatType.GROUP, 3L, "abc");

        verify(repository).deleteAllOnMessage(ChatType.GROUP, 3L, "abc");
    }

    // Reactors are read as id, uid and name only, never as whole accounts with their links.
    private static MessageReactionJpaRepository.ReactorName name(Person person) {
        return new MessageReactionJpaRepository.ReactorName() {
            @Override
            public Long getId() {
                return person.getId();
            }

            @Override
            public String getUid() {
                return person.getUid();
            }

            @Override
            public String getName() {
                return person.getName();
            }
        };
    }

    private static MessageReaction row(ChatType type, long chatId, String messageId, long personId, Reaction reaction) {
        return new MessageReaction(new MessageReaction.Key(type, chatId, messageId, personId, reaction));
    }

    private static Person person(long id, String uid, String name) {
        Person person = new Person();
        // Submitter has no id setter; the DM tests set it the same way.
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(name);
        return person;
    }
}
