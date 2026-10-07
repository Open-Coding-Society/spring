package com.open.spring.mvc.directmessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for direct messages: finding/creating conversations, checking
 * who's allowed to see one, and saving + broadcasting new messages. Kept out of
 * the controller so DirectMessageApiController only has to handle HTTP concerns
 * (this mirrors GroupChatApiController -> GroupChatService/GroupChatRealtimeService).
 */
@Service
@RequiredArgsConstructor
public class DirectMessageService {

    public static final String DM_TOPIC_PREFIX = "/topic/dm/";
    public static final int MAX_BODY_LENGTH = 2000; // matches @Column(length = 2000) on DirectMessage.body

    private final DirectMessageConversationJpaRepository conversationRepository;
    private final DirectMessageJpaRepository messageRepository;
    private final DirectMessageReadStateJpaRepository readStateRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public record UnreadSender(String uid, String name) {
    }

    /**
     * {@code people} is every distinct person with at least one unread message for
     * the viewer (the notification badge counts people, not messages), most recent
     * first; {@code unreadByConversation} is the unread message count per conversation.
     */
    public record UnreadSummary(List<UnreadSender> people, Map<Long, Long> unreadByConversation) {
    }

    /**
     * Finds or creates the conversation between {@code requester} and
     * {@code others}. A 1:1 conversation (exactly one other person) is reused
     * the next time either person starts a chat with the other; a group of 3+
     * people always gets a brand-new conversation, since there's no single
     * "the" conversation for an arbitrary group of participants -- this
     * matches how Groups already has no dedup for its member lists.
     */
    @Transactional
    public DirectMessageConversation getOrCreateConversation(Person requester, List<Person> others) {
        if (requester == null) {
            throw new IllegalArgumentException("requester is required");
        }
        if (others == null || others.isEmpty()) {
            throw new IllegalArgumentException("at least one other participant is required");
        }

        List<Person> dedupedOthers = dedupeParticipants(others, requester);
        if (dedupedOthers.size() == 1) {
            Person other = dedupedOthers.get(0);
            List<DirectMessageConversation> existing = conversationRepository.findByParticipantsContaining(requester);
            for (DirectMessageConversation conversation : existing) {
                List<Person> participants = conversation.getParticipants();
                if (participants.size() == 2 && containsPerson(participants, other)) {
                    return conversation;
                }
            }
        }

        List<Person> participants = new ArrayList<>();
        participants.add(requester);
        participants.addAll(dedupedOthers);

        return conversationRepository.save(new DirectMessageConversation(participants));
    }

    /**
     * The security boundary: only participants may read or post in a
     * conversation. Compared by id rather than Person.equals(), since equals()
     * on JPA entities can be unreliable across separately-loaded instances.
     */
    public boolean isParticipant(DirectMessageConversation conversation, Person person) {
        return containsPerson(conversation.getParticipants(), person);
    }

    @Transactional(readOnly = true)
    public List<DirectMessage> getMessages(DirectMessageConversation conversation) {
        return messageRepository.findByConversationOrderBySentAtAsc(conversation);
    }

    @Transactional
    public DirectMessageEvent postMessage(DirectMessageConversation conversation, Person sender, String body) {
        validateMessageBody(body);
        DirectMessage message = messageRepository.save(new DirectMessage(conversation, sender, body.trim()));

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .type("message")
                .senderUid(sender.getUid())
                .senderName(sender.getName())
                .body(message.getBody())
                .sentAt(message.getSentAt().toString())
                .build();

        // Posting in a conversation means the sender has caught up on it.
        advanceReadMarker(conversation, sender, message.getId());

        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + conversation.getId(), event);
        return event;
    }

    /** Only the original sender may edit their message. Compared by id, like isParticipant. */
    public boolean isSender(DirectMessage message, Person person) {
        return message.getSender().getId().equals(person.getId());
    }

    /**
     * Replaces the body of an existing message and broadcasts an "edited" event on the
     * conversation topic so other participants' open chats update in place. The message
     * id and sentAt don't change, so read markers and unread counts are unaffected.
     */
    @Transactional
    public DirectMessageEvent editMessage(DirectMessage message, String newBody) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        validateMessageBody(newBody);
        message.edit(newBody.trim());
        DirectMessage saved = messageRepository.save(message);

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(saved.getId())
                .conversationId(saved.getConversation().getId())
                .type("edited")
                .senderUid(saved.getSender().getUid())
                .senderName(saved.getSender().getName())
                .body(saved.getBody())
                .sentAt(saved.getSentAt().toString())
                .editedAt(saved.getEditedAt().toString())
                .build();

        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + saved.getConversation().getId(), event);
        return event;
    }

    @Transactional(readOnly = true)
    public UnreadSummary getUnreadSummary(Person person) {
        Map<String, UnreadSender> people = new LinkedHashMap<>();
        Map<Long, Long> unreadByConversation = new LinkedHashMap<>();
        for (UnreadMessageCount row : messageRepository.countUnreadBySender(person)) {
            people.putIfAbsent(row.senderUid(), new UnreadSender(row.senderUid(), row.senderName()));
            unreadByConversation.merge(row.conversationId(), row.unreadCount(), Long::sum);
        }
        return new UnreadSummary(List.copyOf(people.values()), unreadByConversation);
    }

    /** Marks everything currently in the conversation as read for {@code person}. */
    @Transactional
    public void markRead(DirectMessageConversation conversation, Person person) {
        messageRepository.findTopByConversationOrderByIdDesc(conversation)
                .ifPresent(latest -> advanceReadMarker(conversation, person, latest.getId()));
    }

    private void advanceReadMarker(DirectMessageConversation conversation, Person person, Long messageId) {
        DirectMessageReadState state = readStateRepository.findByConversationAndPerson(conversation, person)
                .orElseGet(() -> new DirectMessageReadState(conversation, person));
        if (state.getLastReadMessageId() == null || state.getLastReadMessageId() < messageId) {
            state.setLastReadMessageId(messageId);
            readStateRepository.save(state);
        }
    }

    private boolean containsPerson(List<Person> people, Person target) {
        return people.stream().anyMatch(person -> person.getId().equals(target.getId()));
    }

    private void validateMessageBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (body.trim().length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body is too long");
        }
    }

    private List<Person> dedupeParticipants(List<Person> others, Person requester) {
        Set<Long> seenIds = new LinkedHashSet<>();
        List<Person> deduped = new ArrayList<>();
        for (Person person : others) {
            if (person == null || person.getId() == null || person.getId().equals(requester.getId())) {
                continue;
            }
            if (seenIds.add(person.getId())) {
                deduped.add(person);
            }
        }
        return deduped;
    }
}
