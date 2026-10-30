package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

/**
 * Group chat history is JSON lines in S3. Lines saved before the sender field was renamed
 * from {@code name} to {@code author} must still read back with their sender, and must
 * keep it when the file is written again.
 */
class GroupChatMessageJsonTest {

    // The same defaults Spring Boot gives the ObjectMapper that GroupChatService is injected with.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private static final String SAVED_BEFORE_RENAME =
            "{\"id\":\"m1\",\"name\":\"Ada Lovelace\",\"message\":\"hi\","
            + "\"date\":\"2026-10-20T17:00:00Z\",\"image\":null,\"edited\":false}";

    @Test
    void lineSavedWithNameReadsBackWithItsAuthor() throws Exception {
        GroupChatMessage message = objectMapper.readValue(SAVED_BEFORE_RENAME, GroupChatMessage.class);

        assertEquals("Ada Lovelace", message.getAuthor());
        assertNull(message.getAuthor_uid());
    }

    @Test
    void messageIsWrittenWithAuthorNotName() throws Exception {
        GroupChatMessage message = objectMapper.readValue(SAVED_BEFORE_RENAME, GroupChatMessage.class);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertEquals("Ada Lovelace", json.get("author").asText());
        assertFalse(json.has("name"));
    }

    @Test
    void addingAMessageKeepsTheSenderOfOlderLines() throws Exception {
        S3FileHandler s3 = mock(S3FileHandler.class);
        when(s3.decodeFile("CSA", "messages-images/messages.jsonl")).thenReturn(
                Base64.getEncoder().encodeToString(SAVED_BEFORE_RENAME.getBytes(StandardCharsets.UTF_8)));
        GroupChatService service = new GroupChatService(s3, objectMapper);

        service.addMessage("CSA", new GroupChatMessage("Grace Hopper", "hop", "hello", "2026-10-30T17:00:00Z", null));

        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(s3).uploadFile(uploaded.capture(), eq("messages-images/messages.jsonl"), eq("CSA"));
        String[] lines = new String(Base64.getDecoder().decode(uploaded.getValue()), StandardCharsets.UTF_8).split("\n");
        assertEquals("Ada Lovelace", objectMapper.readTree(lines[0]).get("author").asText());
        assertEquals("Grace Hopper", objectMapper.readTree(lines[1]).get("author").asText());
    }
}
