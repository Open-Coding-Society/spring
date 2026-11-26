package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

/**
 * Two changes to one group's messages at the same time. Sending, editing and deleting each
 * read the group's whole messages file, change it and write it back, and neither change may
 * be lost.
 */
class GroupChatServiceTest {

    private static final String MESSAGES_KEY = "CSA/messages-images/messages.jsonl";

    /**
     * S3 kept in a map, keyed the way S3FileHandler keys objects: "group/filename". A read
     * takes its copy, then waits until a second read has started (or half a second passes),
     * so two writes that are not kept apart both change the same old copy of the file.
     */
    static class OverlappingReads extends S3FileHandler {
        final Map<String, String> objects = new ConcurrentHashMap<>();
        final CountDownLatch bothReading = new CountDownLatch(2);

        @Override
        public String uploadFile(String base64Data, String filename, String uid) {
            objects.put(uid + "/" + filename, base64Data);
            return filename;
        }

        @Override
        public String decodeFile(String uid, String filename) {
            String copy = objects.get(uid + "/" + filename);
            bothReading.countDown();
            try {
                bothReading.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return copy;
        }

        // Its reads never fail, so a send's strict read is the same read.
        @Override
        public String decodeFileOrThrow(String uid, String filename) {
            return decodeFile(uid, filename);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private OverlappingReads storage;
    private GroupChatService chat;

    @BeforeEach
    void setUp() {
        storage = new OverlappingReads();
        chat = new GroupChatService(storage, json);
    }

    @Test
    void twoMessagesSentAtOnceAreBothKept() throws Exception {
        GroupChatMessage ada = message("Ada Lovelace", "ada", "Meeting at 3?");
        GroupChatMessage grace = message("Grace Hopper", "grace", "Works for me");

        atOnce(() -> chat.addMessage("CSA", ada), () -> chat.addMessage("CSA", grace));

        // Either send may get there first, so compare them in alphabetical order.
        List<String> saved = texts(chat.getMessages("CSA")).stream().sorted().collect(Collectors.toList());
        assertEquals(List.of("Meeting at 3?", "Works for me"), saved);
    }

    @Test
    void anEditWhileAnotherMessageIsSentKeepsBothChanges() throws Exception {
        GroupChatMessage ada = message("Ada Lovelace", "ada", "Meeting at 3");
        store(ada);
        GroupChatMessage grace = message("Grace Hopper", "grace", "Works for me");

        atOnce(() -> chat.editMessage("CSA", ada.getId(), "Meeting at 4", "ada"),
                () -> chat.addMessage("CSA", grace));

        List<GroupChatMessage> saved = chat.getMessages("CSA");
        assertEquals(List.of("Meeting at 4", "Works for me"), texts(saved));
        assertTrue(saved.get(0).isEdited());
    }

    @Test
    void aDeleteWhileAnotherMessageIsSentKeepsBothChanges() throws Exception {
        GroupChatMessage plan = message("Ada Lovelace", "ada", "Meeting at 3");
        GroupChatMessage typo = message("Ada Lovelace", "ada", "Meetign at 3");
        store(plan, typo);
        GroupChatMessage grace = message("Grace Hopper", "grace", "Works for me");

        atOnce(() -> chat.deleteMessage("CSA", typo.getId(), "ada"),
                () -> chat.addMessage("CSA", grace));

        assertEquals(List.of("Meeting at 3", "Works for me"), texts(chat.getMessages("CSA")));
    }

    /** Starts both together on their own threads and waits for both, failing if either threw. */
    private static void atOnce(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (Runnable task : List.of(first, second)) {
            done.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> future : done) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static GroupChatMessage message(String author, String uid, String text) {
        return new GroupChatMessage(author, uid, text, "2026-11-21T10:00:00Z", null);
    }

    /** Saves messages straight to the group's file, one JSON object per line, as the chat does. */
    private void store(GroupChatMessage... messages) throws Exception {
        List<String> lines = new ArrayList<>();
        for (GroupChatMessage message : messages) lines.add(json.writeValueAsString(message));
        String jsonl = String.join("\n", lines);
        storage.objects.put(MESSAGES_KEY, Base64.getEncoder().encodeToString(jsonl.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> texts(List<GroupChatMessage> messages) {
        return messages.stream().map(GroupChatMessage::getMessage).collect(Collectors.toList());
    }
}
