package com.open.spring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;
import com.open.spring.mvc.groups.GroupChatMessage;
import com.open.spring.mvc.groups.GroupChatService;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * At every start the server checks each group's chat files in S3 and creates an empty
 * messages file for a group that has none. A check that failed must not count as none:
 * the empty file would replace the group's whole history.
 */
class StartupServiceTest {

    private static final String CSA_MESSAGES = "CSA/messages-images/messages.jsonl";
    private static final String ADA_LINE = "{\"id\":\"m1\",\"author\":\"Ada Lovelace\",\"author_uid\":\"ada\","
            + "\"message\":\"Meeting at 3\",\"date\":\"2026-11-21T10:00:00Z\",\"image\":null,\"edited\":false}";

    /** S3's objects by key. The real S3FileHandler reaches them through a stand-in S3 client. */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    /** Groups S3 can't answer for right now: checking any of their files throws this. */
    private final Map<String, RuntimeException> failingChecks = new ConcurrentHashMap<>();
    private S3Client s3;
    private GroupChatService chat;
    private GroupsJpaRepository groups;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        // As S3 answers: a key that isn't stored is a 404 NoSuchKey.
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            String key = call.<HeadObjectRequest>getArgument(0).key();
            RuntimeException failure = failingChecks.get(key.substring(0, key.indexOf('/')));
            if (failure != null) {
                throw failure;
            }
            if (!objects.containsKey(key)) {
                throw notFound();
            }
            return HeadObjectResponse.builder().build();
        });
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            byte[] bytes = objects.get(call.<GetObjectRequest>getArgument(0).key());
            if (bytes == null) {
                throw notFound();
            }
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            store(call.<PutObjectRequest>getArgument(0).key(), call.<RequestBody>getArgument(1));
            return PutObjectResponse.builder().build();
        });
        S3FileHandler storage = new S3FileHandler();
        ReflectionTestUtils.setField(storage, "s3Client", s3);
        ReflectionTestUtils.setField(storage, "bucketName", "chat-test");
        chat = new GroupChatService(storage, new ObjectMapper());
        groups = mock(GroupsJpaRepository.class);
    }

    @Test
    void aCheckThatFailsLeavesTheHistoryAsItIs() {
        // S3 throttled (503 Slow Down), failing (500), refusing (403) or out of reach: none of
        // these says the file is missing.
        List<RuntimeException> failures = List.of(
                answered(503, "Slow Down"),
                answered(500, "We encountered an internal error. Please try again."),
                answered(403, "Access Denied"),
                SdkClientException.create("Unable to execute HTTP request: Connect timed out"));
        for (RuntimeException failure : failures) {
            objects.clear();
            objects.put(CSA_MESSAGES, ADA_LINE.getBytes(StandardCharsets.UTF_8));
            failingChecks.put("CSA", failure);

            assertDoesNotThrow(() -> startUp("CSA"), failure.toString());

            assertEquals(ADA_LINE, stored(CSA_MESSAGES), failure.toString());
        }
    }

    @Test
    void aCheckThatFailsIsLoggedWithTheGroupAndTheCause() throws Exception {
        objects.put(CSA_MESSAGES, ADA_LINE.getBytes(StandardCharsets.UTF_8));
        RuntimeException throttle = answered(503, "Slow Down");
        failingChecks.put("CSA", throttle);

        try (Warnings warnings = new Warnings(StartupService.class)) {
            startUp("CSA");

            assertEquals(1, warnings.list().size(), warnings.messages().toString());
            ILoggingEvent warning = warnings.list().get(0);
            assertTrue(warning.getFormattedMessage().contains("CSA"), warning.getFormattedMessage());
            assertEquals(throttle.getClass().getName(), warning.getThrowableProxy().getClassName());
        }
    }

    @Test
    void aGroupThatCantBeCheckedDoesNotStopTheOthers() throws Exception {
        objects.put(CSA_MESSAGES, ADA_LINE.getBytes(StandardCharsets.UTF_8));
        failingChecks.put("CSA", answered(503, "Slow Down"));

        startUp("CSA", "CSP");

        assertEquals(ADA_LINE, stored(CSA_MESSAGES));
        assertEquals("", stored("CSP/messages-images/messages.jsonl"));
    }

    @Test
    void aGroupWithNoMessagesFileYetGetsAnEmptyOne() throws Exception {
        // S3's 404: nothing is stored yet, so there's no history to lose.
        startUp("CSA");

        assertEquals("", stored(CSA_MESSAGES));
        assertArrayEquals(new byte[0], objects.get("CSA/shared-files/"));
    }

    @Test
    void aGroupThatHasItsMessagesKeepsThem() throws Exception {
        objects.put(CSA_MESSAGES, ADA_LINE.getBytes(StandardCharsets.UTF_8));

        startUp("CSA");

        assertEquals(ADA_LINE, stored(CSA_MESSAGES));
    }

    @Test
    void aServerWithoutS3StartsQuietly() {
        // A local server without S3 settings stores nothing, so there's nothing to check.
        chat = new GroupChatService(new S3FileHandler(), new ObjectMapper());

        try (Warnings warnings = new Warnings(StartupService.class, GroupChatService.class, S3FileHandler.class)) {
            assertDoesNotThrow(() -> startUp("CSA", "CSP"));
            assertEquals(List.of(), warnings.messages());
        }
    }

    @Test
    void aMessageSentWhileTheCheckCreatesTheFileIsKept() throws Exception {
        // The check finds no file, but S3's answer only arrives once a send has saved a
        // message (or half a second passes). An empty file written after that would erase it.
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch sendSaved = new CountDownLatch(1);
        doAnswer(call -> {
            String key = call.<HeadObjectRequest>getArgument(0).key();
            boolean found = objects.containsKey(key);
            if (CSA_MESSAGES.equals(key)) {
                checking.countDown();
                sendSaved.await(500, TimeUnit.MILLISECONDS);
            }
            if (!found) {
                throw notFound();
            }
            return HeadObjectResponse.builder().build();
        }).when(s3).headObject(any(HeadObjectRequest.class));
        doAnswer(call -> {
            String key = call.<PutObjectRequest>getArgument(0).key();
            store(key, call.<RequestBody>getArgument(1));
            if (CSA_MESSAGES.equals(key) && objects.get(key).length > 0) {
                sendSaved.countDown();
            }
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> send = pool.submit(() -> {
                checking.await(5, TimeUnit.SECONDS);
                return chat.addMessage("CSA",
                        new GroupChatMessage("Grace Hopper", "grace", "Works for me", "2026-11-22T08:00:00Z", null));
            });
            startUp("CSA");
            send.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of("Works for me"),
                chat.getMessages("CSA").stream().map(GroupChatMessage::getMessage).toList());
    }

    /** Runs the server's start-up check over groups with these names. */
    private void startUp(String... groupNames) throws Exception {
        List<Groups> found = new ArrayList<>();
        for (String name : groupNames) {
            Groups group = new Groups();
            group.setName(name);
            found.add(group);
        }
        when(groups.findAll()).thenReturn(found);
        new StartupService(groups, chat).initializeGroupStorage().run(new DefaultApplicationArguments());
    }

    private void store(String key, RequestBody body) throws Exception {
        try (InputStream bytes = body.contentStreamProvider().newStream()) {
            objects.put(key, bytes.readAllBytes());
        }
    }

    private String stored(String key) {
        byte[] bytes = objects.get(key);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static S3Exception answered(int status, String message) {
        return (S3Exception) S3Exception.builder().statusCode(status).message(message).build();
    }

    private static S3Exception notFound() {
        return (S3Exception) NoSuchKeyException.builder().statusCode(404).message("The specified key does not exist.").build();
    }

    /** The warnings the given classes log while it's open. */
    private static final class Warnings implements AutoCloseable {
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final List<Logger> loggers = new ArrayList<>();
        private final List<Level> previousLevels = new ArrayList<>();

        Warnings(Class<?>... types) {
            appender.start();
            for (Class<?> type : types) {
                Logger logger = (Logger) LoggerFactory.getLogger(type);
                previousLevels.add(logger.getLevel());
                logger.setLevel(Level.WARN);
                logger.addAppender(appender);
                loggers.add(logger);
            }
        }

        List<ILoggingEvent> list() {
            return List.copyOf(appender.list);
        }

        List<String> messages() {
            return list().stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(appender);
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            appender.stop();
        }
    }
}
