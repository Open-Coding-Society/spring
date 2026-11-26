package com.open.spring.mvc.reactions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

import lombok.RequiredArgsConstructor;

/**
 * Saves and summarizes the reactions on DM and group messages. It doesn't decide who may react
 * or whether a message exists: each chat's own service checks that before calling here.
 */
@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private static final Comparator<Reactor> BY_NAME = Comparator
            .comparing((Reactor reactor) -> reactor.name() == null ? "" : reactor.name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(reactor -> reactor.uid() == null ? "" : reactor.uid());

    private final MessageReactionJpaRepository repository;

    /** Someone who reacted, as the page shows them. */
    public record Reactor(String uid, String name) {
    }

    /** One emoji on a message, by its lowercase key, and everyone who left it. */
    public record ReactionSummary(String reaction, List<Reactor> people) {
    }

    /** A message's reactions after a change: the body PUT and DELETE answer with. */
    public record MessageReactions(String messageId, List<ReactionSummary> reactions) {
    }

    /** Adds this person's reaction. Adding one that is already there changes nothing. */
    @Transactional
    public void add(ChatType type, Long chatId, String messageId, Person person, Reaction reaction) {
        MessageReaction.Key key = new MessageReaction.Key(type, chatId, messageId, person.getId(), reaction);
        if (!repository.existsById(key)) {
            repository.save(new MessageReaction(key));
        }
    }

    /** Removes this person's reaction. Removing one that isn't there changes nothing. */
    @Transactional
    public void remove(ChatType type, Long chatId, String messageId, Person person, Reaction reaction) {
        repository.deleteOne(type, chatId, messageId, person.getId(), reaction);
    }

    /** One message's reactions, in the enum's order. */
    @Transactional(readOnly = true)
    public List<ReactionSummary> forMessage(ChatType type, Long chatId, String messageId) {
        List<MessageReaction> rows = repository.findByKeyChatTypeAndKeyChatIdAndKeyMessageId(type, chatId, messageId);
        return summarize(rows, reactorsFor(rows));
    }

    /** Every reacted-to message in a chat, by message id. A message left with no reactions isn't listed. */
    @Transactional(readOnly = true)
    public Map<String, List<ReactionSummary>> forChat(ChatType type, Long chatId) {
        List<MessageReaction> rows = repository.findByKeyChatTypeAndKeyChatId(type, chatId);
        Map<Long, Reactor> reactors = reactorsFor(rows);
        Map<String, List<MessageReaction>> byMessage = rows.stream().collect(
                Collectors.groupingBy(row -> row.getKey().getMessageId(), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<ReactionSummary>> result = new LinkedHashMap<>();
        byMessage.forEach((messageId, messageRows) -> {
            List<ReactionSummary> summaries = summarize(messageRows, reactors);
            if (!summaries.isEmpty()) {
                result.put(messageId, summaries);
            }
        });
        return result;
    }

    /** Removes every reaction on a message, as when the message is deleted. */
    @Transactional
    public void removeAllFor(ChatType type, Long chatId, String messageId) {
        repository.deleteAllOnMessage(type, chatId, messageId);
    }

    // Everyone in one query. Someone whose account is gone isn't in the result, so they drop out.
    private Map<Long, Reactor> reactorsFor(List<MessageReaction> rows) {
        List<Long> ids = rows.stream().map(row -> row.getKey().getPersonId()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Reactor> reactors = new HashMap<>();
        for (MessageReactionJpaRepository.ReactorName person : repository.findReactorNames(ids)) {
            reactors.put(person.getId(), new Reactor(person.getUid(), person.getName()));
        }
        return reactors;
    }

    private List<ReactionSummary> summarize(List<MessageReaction> rows, Map<Long, Reactor> reactors) {
        List<ReactionSummary> summaries = new ArrayList<>();
        for (Reaction reaction : Reaction.values()) {
            List<Reactor> people = rows.stream()
                    .filter(row -> row.getKey().getReaction() == reaction)
                    .map(row -> reactors.get(row.getKey().getPersonId()))
                    .filter(Objects::nonNull)
                    .sorted(BY_NAME)
                    .toList();
            if (!people.isEmpty()) {
                summaries.add(new ReactionSummary(reaction.key(), people));
            }
        }
        return summaries;
    }
}
