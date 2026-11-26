package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.reactions.MessageReactionService;

class GroupChatRegressionTest {
    private static final String MESSAGES_FILE = "messages-images/messages.jsonl";

    private S3FileHandler storage;
    private GroupChatService chat;
    private GroupsJpaRepository groups;
    private GroupChatReadStateJpaRepository readStates;
    private GroupChatReadService reads;
    private GroupChatRealtimeService realtime;
    private GroupChatApiController controller;
    private Person person;
    private GroupChatMessage message;
    private List<GroupChatMessage> messages;

    @BeforeEach
    void setUp() {
        storage = mock(S3FileHandler.class);
        chat = spy(new GroupChatService(storage, new ObjectMapper()));
        groups = mock(GroupsJpaRepository.class);
        readStates = mock(GroupChatReadStateJpaRepository.class);
        reads = new GroupChatReadService(readStates, groups, chat);
        realtime = new GroupChatRealtimeService(chat, groups, mock(SimpMessagingTemplate.class), mock(MessageReactionService.class));
        PersonJpaRepository people = mock(PersonJpaRepository.class);
        controller = new GroupChatApiController(chat, realtime, reads, groups, people, mock(MessageReactionService.class));

        Groups group = spy(new Groups());
        when(group.getId()).thenReturn(7L);
        group.setName("CSA");
        when(groups.findById(7L)).thenReturn(Optional.of(group));
        person = spy(new Person());
        when(person.getId()).thenReturn(1L);
        person.setUid("owner");
        person.setName("Shared Name");
        when(people.findByUid("owner")).thenReturn(person);
        Person other = new Person();
        other.setUid("other");
        other.setName("Shared Name");
        when(people.findByUid("other")).thenReturn(other);
        when(groups.findGroupsByPersonId(1L)).thenReturn(List.of(group));
        when(readStates.findByPerson(person)).thenReturn(List.of());

        message = new GroupChatMessage("Shared Name", "owner", "original",
                "2026-10-30T00:00:00Z", null);
        messages = new ArrayList<>(List.of(message));
        doReturn(messages).when(chat).getMessages("CSA");
        // Edits and deletes read the messages file strictly and save it back, so S3 stands in
        // for both: a read answers what's in `messages` at that moment, and a save replaces it.
        when(storage.isConfigured()).thenReturn(true);
        when(storage.decodeFileOrThrow("CSA", MESSAGES_FILE)).thenAnswer(call -> jsonlBase64(messages));
        doAnswer(call -> {
            List<GroupChatMessage> saved = parse(call.getArgument(0));
            messages.clear();
            messages.addAll(saved);
            return MESSAGES_FILE;
        }).when(storage).uploadFileOrThrow(anyString(), eq(MESSAGES_FILE), eq("CSA"));
    }

    private String jsonlBase64(List<GroupChatMessage> stored) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String jsonl = stored.stream().map(m -> {
            try {
                return json.writeValueAsString(m);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.joining("\n"));
        return Base64.getEncoder().encodeToString(jsonl.getBytes(StandardCharsets.UTF_8));
    }

    /** The messages each save wrote, in order. */
    private List<List<GroupChatMessage>> saves(int expected) throws Exception {
        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(storage, times(expected)).uploadFileOrThrow(uploaded.capture(), eq(MESSAGES_FILE), eq("CSA"));
        List<List<GroupChatMessage>> saves = new ArrayList<>();
        for (String base64 : uploaded.getAllValues()) {
            saves.add(parse(base64));
        }
        return saves;
    }

    private static List<GroupChatMessage> parse(String base64) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String jsonl = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        List<GroupChatMessage> saved = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (!line.isBlank()) saved.add(json.readValue(line, GroupChatMessage.class));
        }
        return saved;
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanEditAndDeleteThroughRestAfterDisplayNameChange() throws Exception {
        person.setName("Renamed Owner");
        signIn("owner");
        GroupChatMessage request = new GroupChatMessage();
        request.setMessage("updated");

        assertEquals(HttpStatus.OK, controller.editMessage(7L, message.getId(), request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.deleteMessage(7L, message.getId()).getStatusCode());
        List<List<GroupChatMessage>> saves = saves(2);
        assertEquals(1, saves.get(0).size());
        assertEquals("updated", saves.get(0).get(0).getMessage());
        assertTrue(saves.get(0).get(0).isEdited());
        assertTrue(saves.get(1).isEmpty());
        assertTrue(messages.isEmpty());
    }

    @Test
    void sameDisplayNameDoesNotAuthorizeAnotherAccount() {
        signIn("other");
        GroupChatMessage request = new GroupChatMessage();
        request.setMessage("spoofed");

        assertEquals(HttpStatus.FORBIDDEN, controller.editMessage(7L, message.getId(), request).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.deleteMessage(7L, message.getId()).getStatusCode());
        assertEquals(1, messages.size());
        assertEquals("original", messages.get(0).getMessage());
        verify(storage, never()).uploadFileOrThrow(anyString(), anyString(), anyString());
    }

    @Test
    void unauthenticatedRequestsCannotEditOrDelete() {
        SecurityContextHolder.clearContext();
        GroupChatMessage request = new GroupChatMessage();
        request.setMessage("updated");

        assertEquals(HttpStatus.UNAUTHORIZED, controller.editMessage(7L, message.getId(), request).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.deleteMessage(7L, message.getId()).getStatusCode());
        verify(storage, never()).uploadFileOrThrow(anyString(), anyString(), anyString());
    }

    @Test
    void missingAuthorUidDoesNotFallBackToDisplayName() {
        message.setAuthor_uid(null);

        assertThrows(SecurityException.class,
                () -> chat.editMessage("CSA", message.getId(), "updated", "owner"));
        assertThrows(SecurityException.class,
                () -> chat.deleteMessage("CSA", message.getId(), "owner"));
        verify(storage, never()).uploadFileOrThrow(anyString(), anyString(), anyString());
    }

    @Test
    void unreadCountsCompareEachMessagesUidWithoutSecurityContext() {
        SecurityContextHolder.clearContext();
        messages.add(new GroupChatMessage("Shared Name", "other", "other message",
                "2026-10-30T00:00:01Z", null));
        messages.add(new GroupChatMessage("Shared Name", null, "legacy message",
                "2026-10-30T00:00:02Z", null));

        assertEquals(Map.of(7L, 2L), reads.unreadByGroup(person));
        person.setName("owner");
        assertEquals(Map.of(7L, 2L), reads.unreadByGroup(person));
    }

    private void signIn(String uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }
}
