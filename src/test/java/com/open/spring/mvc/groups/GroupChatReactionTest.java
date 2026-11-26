package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.reactions.ChatType;
import com.open.spring.mvc.reactions.MessageReactionService;
import com.open.spring.mvc.reactions.MessageReactionService.MessageReactions;
import com.open.spring.mvc.reactions.MessageReactionService.ReactionSummary;
import com.open.spring.mvc.reactions.MessageReactionService.Reactor;
import com.open.spring.mvc.reactions.Reaction;

/**
 * Reacting to a group chat message: signed in, a real message id, broadcast as a
 * reactionsServer frame, and never a read or write of the group's S3 message file.
 */
class GroupChatReactionTest {

    private static final String MESSAGE_ID = "3f2c8a8e-5b1d-4c7e-9a52-8f0e6d4b2a10";

    private S3FileHandler storage;
    private GroupChatService chat;
    private SimpMessagingTemplate messaging;
    private MessageReactionService reactionService;
    private GroupChatRealtimeService realtime;
    private GroupChatApiController controller;
    private Person ada;
    private List<ReactionSummary> summaries;

    @BeforeEach
    void setUp() {
        storage = mock(S3FileHandler.class);
        chat = spy(new GroupChatService(storage, new ObjectMapper()));
        GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
        messaging = mock(SimpMessagingTemplate.class);
        reactionService = mock(MessageReactionService.class);
        realtime = new GroupChatRealtimeService(chat, groups, messaging, reactionService);
        PersonJpaRepository people = mock(PersonJpaRepository.class);
        GroupChatReadService reads = new GroupChatReadService(mock(GroupChatReadStateJpaRepository.class), groups, chat);
        controller = new GroupChatApiController(chat, realtime, reads, groups, people, reactionService);

        Groups group = spy(new Groups());
        when(group.getId()).thenReturn(3L);
        group.setName("Team Three");
        when(groups.findById(3L)).thenReturn(Optional.of(group));

        ada = new Person();
        ReflectionTestUtils.setField(ada, "id", 1L);
        ada.setUid("ada");
        ada.setName("Ada");
        when(people.findByUid("ada")).thenReturn(ada);

        summaries = List.of(new ReactionSummary("love", List.of(new Reactor("ada", "Ada"))));
        when(reactionService.forMessage(ChatType.GROUP, 3L, MESSAGE_ID)).thenReturn(summaries);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReactionGoesOutAsAReactionsServerFrame() {
        List<ReactionSummary> result = realtime.setReaction(3L, ada, MESSAGE_ID, Reaction.LOVE, true);

        verify(reactionService).add(ChatType.GROUP, 3L, MESSAGE_ID, ada, Reaction.LOVE);
        ArgumentCaptor<GroupChatEvent> frame = ArgumentCaptor.forClass(GroupChatEvent.class);
        verify(messaging).convertAndSend(eq("/topic/group/3"), frame.capture());
        assertEquals("reactionsServer", frame.getValue().getContext());
        assertEquals(3L, frame.getValue().getGroupId());
        assertEquals(MESSAGE_ID, frame.getValue().getMessageId());
        assertEquals(summaries, frame.getValue().getReactions());
        assertEquals(summaries, result);
    }

    @Test
    void aReactionNeverTouchesTheGroupsFile() {
        signIn("ada");

        ResponseEntity<?> added = controller.addReaction(3L, MESSAGE_ID, "love");
        ResponseEntity<?> removed = controller.removeReaction(3L, MESSAGE_ID, "love");
        when(reactionService.forChat(ChatType.GROUP, 3L)).thenReturn(java.util.Map.of(MESSAGE_ID, summaries));
        ResponseEntity<?> listed = controller.getReactions(3L);

        assertEquals(HttpStatus.OK, added.getStatusCode());
        assertEquals(new MessageReactions(MESSAGE_ID, summaries), added.getBody());
        assertEquals(HttpStatus.OK, removed.getStatusCode());
        verify(reactionService).remove(ChatType.GROUP, 3L, MESSAGE_ID, ada, Reaction.LOVE);
        assertEquals(java.util.Map.of(MESSAGE_ID, summaries), listed.getBody());
        verifyNoInteractions(storage);
    }

    @Test
    void signedOutIs401() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.addReaction(3L, MESSAGE_ID, "love").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.removeReaction(3L, MESSAGE_ID, "love").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.getReactions(3L).getStatusCode());
        verifyNoInteractions(reactionService);
    }

    @Test
    void aBadMessageIdIs400() {
        signIn("ada");

        assertEquals(HttpStatus.BAD_REQUEST, controller.addReaction(3L, "not-a-message", "love").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.addReaction(3L, "../shared-files", "love").getStatusCode());
        // Saved ids are lowercase; an uppercase one would match some lookups and not others on MySQL.
        assertEquals(HttpStatus.BAD_REQUEST, controller.addReaction(3L, MESSAGE_ID.toUpperCase(), "love").getStatusCode());
        verify(reactionService, never()).add(any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownReactionIs400() {
        signIn("ada");

        assertEquals(HttpStatus.BAD_REQUEST, controller.addReaction(3L, MESSAGE_ID, "thumbsup").getStatusCode());
        verify(reactionService, never()).add(any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownGroupIs404() {
        signIn("ada");

        assertEquals(HttpStatus.NOT_FOUND, controller.addReaction(99L, MESSAGE_ID, "love").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getReactions(99L).getStatusCode());
        verifyNoInteractions(reactionService);
    }

    @Test
    void deletingAMessageRemovesItsReactions() {
        GroupChatMessage saved = new GroupChatMessage("Ada", "ada", "hello", "2026-11-21T00:00:00Z", null);
        // The chat's delete reads S3 itself and says whether it found the message.
        doReturn(true).when(chat).deleteMessage("Team Three", saved.getId(), "ada");

        realtime.deleteMessage(3L, saved.getId(), "ada");

        verify(reactionService).removeAllFor(ChatType.GROUP, 3L, saved.getId());
    }

    @Test
    void deletingAMessageThatIsntSavedKeepsItsReactions() {
        doReturn(false).when(chat).deleteMessage("Team Three", MESSAGE_ID, "ada");

        assertFalse(realtime.deleteMessage(3L, MESSAGE_ID, "ada"));

        verify(reactionService, never()).removeAllFor(any(), any(), any());
    }

    @Test
    void onlyAReactionsFrameCarriesReactions() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String messageFrame = json.writeValueAsString(GroupChatEvent.builder()
                .context("sendMessageServer").groupId(3L).messageId(MESSAGE_ID).message("hi").build());
        String reactionsFrame = json.writeValueAsString(GroupChatEvent.builder()
                .context("reactionsServer").groupId(3L).messageId(MESSAGE_ID).reactions(summaries).build());

        assertFalse(messageFrame.contains("\"reactions\""));
        assertTrue(reactionsFrame.contains("\"reactions\""));
    }

    private void signIn(String uid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }
}
