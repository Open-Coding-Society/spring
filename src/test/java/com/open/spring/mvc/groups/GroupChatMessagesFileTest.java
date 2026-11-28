package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

/**
 * A group's messages file can hold a line that doesn't read as a message, and a message can
 * fail to be written. Neither may quietly lose a message when the file is saved again.
 */
class GroupChatMessagesFileTest {

    private static final String MESSAGES_KEY = "CSA/messages-images/messages.jsonl";
    private static final String ADA_LINE = "{\"id\":\"m1\",\"author\":\"Ada Lovelace\",\"author_uid\":\"ada\","
            + "\"message\":\"Meeting at 3\",\"date\":\"2026-11-21T10:00:00Z\",\"image\":null,\"edited\":false}";
    private static final String GRACE_LINE = "{\"id\":\"m2\",\"author\":\"Grace Hopper\",\"author_uid\":\"grace\","
            + "\"message\":\"Meetign at 3\",\"date\":\"2026-11-21T10:05:00Z\",\"image\":null,\"edited\":false}";
    // A line that isn't a whole message: S3 holds it, but it doesn't read as JSON.
    private static final String UNREADABLE = "{\"id\":\"m0\",\"author\":\"Ada Lovelace\",\"message\":\"Agenda for";

    /** A configured S3 kept in a map, whose reads and writes never fail. */
    static class InMemoryS3 extends S3FileHandler {
        final Map<String, String> objects = new ConcurrentHashMap<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String decodeFile(String uid, String filename) {
            return objects.get(uid + "/" + filename);
        }

        @Override
        public String decodeFileOrThrow(String uid, String filename) {
            return decodeFile(uid, filename);
        }

        @Override
        public String uploadFileOrThrow(String base64Data, String filename, String uid) {
            objects.put(uid + "/" + filename, base64Data);
            return filename;
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryS3 storage;
    private GroupChatService chat;

    @BeforeEach
    void setUp() {
        storage = new InMemoryS3();
        chat = new GroupChatService(storage, json);
    }

    private void store(String... lines) {
        String jsonl = String.join("\n", lines);
        storage.objects.put(MESSAGES_KEY, Base64.getEncoder().encodeToString(jsonl.getBytes(StandardCharsets.UTF_8)));
    }

    private List<String> storedLines() {
        String base64 = storage.objects.get(MESSAGES_KEY);
        return List.of(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8).split("\n"));
    }

    private String text(String line) throws Exception {
        return json.readValue(line, GroupChatMessage.class).getMessage();
    }

    @Test
    void anUnreadableLineIsKeptWhereItWasWhenAMessageIsSent() throws Exception {
        store(ADA_LINE, UNREADABLE, GRACE_LINE);

        chat.addMessage("CSA", new GroupChatMessage("Grace Hopper", "grace", "Works for me", "2026-11-21T10:06:00Z", null));

        List<String> lines = storedLines();
        assertEquals(4, lines.size(), lines.toString());
        assertEquals("Meeting at 3", text(lines.get(0)));
        assertEquals(UNREADABLE, lines.get(1));
        assertEquals("Meetign at 3", text(lines.get(2)));
        assertEquals("Works for me", text(lines.get(3)));
    }

    @Test
    void anUnreadableLineIsKeptWhenAMessageIsEditedOrDeleted() throws Exception {
        store(ADA_LINE, UNREADABLE, GRACE_LINE);

        assertTrue(chat.editMessage("CSA", "m2", "Meeting at 3", "grace").isPresent());
        List<String> lines = storedLines();
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(List.of("Meeting at 3", UNREADABLE, "Meeting at 3"),
                List.of(text(lines.get(0)), lines.get(1), text(lines.get(2))));

        assertTrue(chat.deleteMessage("CSA", "m1", "ada"));
        lines = storedLines();
        assertEquals(2, lines.size(), lines.toString());
        assertEquals(UNREADABLE, lines.get(0));
        assertEquals("Meeting at 3", text(lines.get(1)));
    }

    @Test
    void loadingTheChatSkipsAnUnreadableLine() {
        store(ADA_LINE, UNREADABLE, GRACE_LINE);

        List<String> texts = chat.getMessages("CSA").stream().map(GroupChatMessage::getMessage).toList();

        assertEquals(List.of("Meeting at 3", "Meetign at 3"), texts);
    }

    @Test
    void aMessageThatCantBeWrittenRefusesTheChangeAndKeepsTheFile() {
        ObjectMapper failsOnOneMessage = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                if (value instanceof GroupChatMessage message && "can't write me".equals(message.getMessage())) {
                    throw new JsonMappingException(null, "can't write this message");
                }
                return super.writeValueAsString(value);
            }
        };
        chat = new GroupChatService(storage, failsOnOneMessage);
        store(ADA_LINE);

        assertThrows(RuntimeException.class, () -> chat.addMessage("CSA",
                new GroupChatMessage("Grace Hopper", "grace", "can't write me", "2026-11-21T10:06:00Z", null)));

        assertEquals(List.of(ADA_LINE), storedLines());
    }
}
