package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.push.PushNotificationService;
import com.open.spring.mvc.reactions.ChatType;
import com.open.spring.mvc.reactions.MessageReactionService;
import com.open.spring.mvc.reactions.MessageReactionService.MessageReactions;
import com.open.spring.mvc.reactions.MessageReactionService.ReactionSummary;
import com.open.spring.mvc.reactions.MessageReactionService.Reactor;
import com.open.spring.mvc.reactions.Reaction;

/**
 * Reacting to a direct message: only participants, never on a deleted message, broadcast as a
 * "reactions" frame, and never a phone notification or a change to what is unread.
 */
class DirectMessageReactionTest {

    private DirectMessageConversationJpaRepository conversationRepository;
    private DirectMessageJpaRepository messageRepository;
    private DirectMessageReadStateJpaRepository readStateRepository;
    private SimpMessagingTemplate messagingTemplate;
    private PushNotificationService pushNotificationService;
    private MessageReactionService reactionService;
    private PersonJpaRepository personRepository;
    private DirectMessageService service;
    private DirectMessageApiController controller;
    private Person alice;
    private Person bob;
    private DirectMessageConversation conversation;
    private DirectMessage message;
    private List<ReactionSummary> summaries;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(DirectMessageConversationJpaRepository.class);
        messageRepository = mock(DirectMessageJpaRepository.class);
        readStateRepository = mock(DirectMessageReadStateJpaRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        pushNotificationService = mock(PushNotificationService.class);
        reactionService = mock(MessageReactionService.class);
        personRepository = mock(PersonJpaRepository.class);
        service = new DirectMessageService(conversationRepository, messageRepository, readStateRepository,
                messagingTemplate, pushNotificationService, reactionService);
        controller = new DirectMessageApiController(service, conversationRepository, messageRepository,
                personRepository, reactionService);

        alice = person(1L, "alice");
        bob = person(2L, "bob");
        Person carol = person(3L, "carol");
        when(personRepository.findByUid("alice")).thenReturn(alice);
        when(personRepository.findByUid("carol")).thenReturn(carol);

        conversation = new DirectMessageConversation(List.of(alice, bob));
        ReflectionTestUtils.setField(conversation, "id", 7L);
        when(conversationRepository.findById(7L)).thenReturn(Optional.of(conversation));

        message = new DirectMessage(conversation, bob, "did anyone finish the FRQ?");
        ReflectionTestUtils.setField(message, "id", 42L);
        when(messageRepository.findByIdAndConversation(42L, conversation)).thenReturn(Optional.of(message));

        summaries = List.of(new ReactionSummary("like", List.of(new Reactor("alice", "alice"))));
        when(reactionService.forMessage(ChatType.DM, 7L, "42")).thenReturn(summaries);
    }

    @Test
    void aReactionGoesOutAsAReactionsFrame() {
        List<ReactionSummary> result = service.setReaction(conversation, alice, 42L, Reaction.LIKE, true);

        verify(reactionService).add(ChatType.DM, 7L, "42", alice, Reaction.LIKE);
        ArgumentCaptor<DirectMessageEvent> frame = ArgumentCaptor.forClass(DirectMessageEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/dm/7"), frame.capture());
        assertEquals("reactions", frame.getValue().getType());
        assertEquals(42L, frame.getValue().getId());
        assertEquals(7L, frame.getValue().getConversationId());
        assertEquals(summaries, frame.getValue().getReactions());
        assertEquals(summaries, result);
    }

    @Test
    void takingAReactionBackRemovesIt() {
        service.setReaction(conversation, alice, 42L, Reaction.LIKE, false);

        verify(reactionService).remove(ChatType.DM, 7L, "42", alice, Reaction.LIKE);
        verify(reactionService, never()).add(any(), any(), any(), any(), any());
    }

    @Test
    void aReactionSendsNoPushAndLeavesReadStateAlone() {
        service.setReaction(conversation, alice, 42L, Reaction.LIKE, true);

        verifyNoInteractions(pushNotificationService, readStateRepository);
    }

    @Test
    void aDeletedMessageCannotBeReactedTo() {
        message.setDeleted(true);

        assertThrows(IllegalStateException.class,
                () -> service.setReaction(conversation, alice, 42L, Reaction.LIKE, true));
        assertEquals(HttpStatus.CONFLICT, controller.addReaction(signedIn("alice"), 7L, 42L, "like").getStatusCode());
        verify(reactionService, never()).add(any(), any(), any(), any(), any());
    }

    @Test
    void aMessageFromAnotherConversationIsNotFound() {
        when(messageRepository.findByIdAndConversation(99L, conversation)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.setReaction(conversation, alice, 99L, Reaction.LIKE, true));
        assertEquals(HttpStatus.NOT_FOUND, controller.addReaction(signedIn("alice"), 7L, 99L, "like").getStatusCode());
    }

    @Test
    void anUnknownReactionIs400() {
        ResponseEntity<Object> response = controller.addReaction(signedIn("alice"), 7L, 42L, "thumbsup");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(reactionService, never()).add(any(), any(), any(), any(), any());
    }

    @Test
    void onlyASignedInParticipantMayReactOrList() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.addReaction(null, 7L, 42L, "like").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.addReaction(signedIn("carol"), 7L, 42L, "like").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.removeReaction(signedIn("carol"), 7L, 42L, "like").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.getReactions(signedIn("carol"), 7L).getStatusCode());
        verifyNoInteractions(reactionService);
    }

    @Test
    void theRoutesAnswerWithTheMessagesReactions() {
        ResponseEntity<Object> added = controller.addReaction(signedIn("alice"), 7L, 42L, "like");
        assertEquals(HttpStatus.OK, added.getStatusCode());
        assertEquals(new MessageReactions("42", summaries), added.getBody());

        ResponseEntity<Object> removed = controller.removeReaction(signedIn("alice"), 7L, 42L, "like");
        assertEquals(HttpStatus.OK, removed.getStatusCode());
        verify(reactionService).remove(ChatType.DM, 7L, "42", alice, Reaction.LIKE);

        Map<String, List<ReactionSummary>> byMessage = Map.of("42", summaries);
        when(reactionService.forChat(ChatType.DM, 7L)).thenReturn(byMessage);
        ResponseEntity<Object> listed = controller.getReactions(signedIn("alice"), 7L);
        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals(byMessage, listed.getBody());
    }

    @Test
    void deletingAMessageRemovesItsReactions() {
        service.deleteMessage(conversation, bob, 42L);

        verify(reactionService).removeAllFor(ChatType.DM, 7L, "42");
    }

    @Test
    void onlyAReactionsFrameCarriesReactions() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String messageFrame = json.writeValueAsString(
                DirectMessageEvent.builder().type("message").id(1L).conversationId(7L).body("hi").build());
        String reactionsFrame = json.writeValueAsString(
                DirectMessageEvent.builder().type("reactions").id(1L).conversationId(7L).reactions(summaries).build());

        assertFalse(messageFrame.contains("\"reactions\""));
        assertTrue(reactionsFrame.contains("\"reactions\""));
    }

    private static UserDetails signedIn(String uid) {
        return User.withUsername(uid).password("unused").build();
    }

    private static Person person(Long id, String uid) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        return person;
    }
}
