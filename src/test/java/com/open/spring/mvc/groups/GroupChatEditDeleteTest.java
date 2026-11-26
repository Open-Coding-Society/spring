package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Editing and deleting a message read the group's whole messages file, change it and write the
 * file back. When S3 can't do either for a moment, the route must say so (503) instead of
 * answering as if the change was saved.
 */
class GroupChatEditDeleteTest {

    private static final String MESSAGES_KEY = "CSA/messages-images/messages.jsonl";
    private static final String GRACE_LINE = "{\"id\":\"m2\",\"author\":\"Grace Hopper\",\"author_uid\":\"grace\","
            + "\"message\":\"Meetign at 3\",\"date\":\"2026-11-21T10:05:00Z\",\"image\":null,\"edited\":false}";

    /** S3's objects by key. The real S3FileHandler reads and writes them through a stand-in S3 client. */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private S3Client s3;
    private SimpMessagingTemplate messaging;
    private GroupChatApiController controller;

    @BeforeEach
    void setUp() {
        useFreshStorage();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** Grace's message in a bucket behind a real S3FileHandler, as S3 answers: a missing key is a 404. */
    private void useFreshStorage() {
        objects.clear();
        objects.put(MESSAGES_KEY, GRACE_LINE.getBytes(StandardCharsets.UTF_8));
        s3 = mock(S3Client.class);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            byte[] bytes = objects.get(call.<GetObjectRequest>getArgument(0).key());
            if (bytes == null) {
                throw NoSuchKeyException.builder().statusCode(404).message("The specified key does not exist.").build();
            }
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            try (InputStream body = call.<RequestBody>getArgument(1).contentStreamProvider().newStream()) {
                objects.put(call.<PutObjectRequest>getArgument(0).key(), body.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        S3FileHandler storage = new S3FileHandler();
        ReflectionTestUtils.setField(storage, "s3Client", s3);
        ReflectionTestUtils.setField(storage, "bucketName", "chat-test");

        GroupChatService chat = new GroupChatService(storage, new ObjectMapper());
        GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
        messaging = mock(SimpMessagingTemplate.class);
        // Built from whatever their constructors take, so a new dependency elsewhere doesn't break this test.
        GroupChatRealtimeService realtime = TestBeans.build(GroupChatRealtimeService.class, chat, groups, messaging);
        GroupChatReadService reads = TestBeans.build(GroupChatReadService.class,
                mock(GroupChatReadStateJpaRepository.class), groups, chat);
        PersonJpaRepository people = mock(PersonJpaRepository.class);
        controller = TestBeans.build(GroupChatApiController.class, chat, realtime, reads, groups, people);

        Groups group = new Groups();
        group.setName("CSA");
        when(groups.findById(7L)).thenReturn(Optional.of(group));
        Person grace = new Person();
        grace.setUid("grace");
        grace.setName("Grace Hopper");
        when(people.findByUid("grace")).thenReturn(grace);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("grace", null, List.of()));
    }

    private ResponseEntity<?> edit() {
        GroupChatMessage request = new GroupChatMessage();
        request.setMessage("Meeting at 3");
        return controller.editMessage(7L, "m2", request);
    }

    private ResponseEntity<?> delete() {
        return controller.deleteMessage(7L, "m2");
    }

    private String storedMessages() {
        byte[] bytes = objects.get(MESSAGES_KEY);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** S3 fails the read of the messages file, or the upload after it. */
    private void failS3(boolean onRead, RuntimeException failure) {
        if (onRead) {
            doThrow(failure).when(s3).getObjectAsBytes(any(GetObjectRequest.class));
        } else {
            doThrow(failure).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }
    }

    /** S3 failing for a moment, on the read or the upload, gets a 503 with Retry-After and changes nothing. */
    private void assertRefusedForNow(Supplier<ResponseEntity<?>> change) {
        for (boolean onRead : List.of(true, false)) {
            for (RuntimeException failure : S3Failures.unavailable()) {
                useFreshStorage();
                failS3(onRead, failure);
                String what = (onRead ? "read: " : "upload: ") + failure;

                ResponseEntity<?> response = change.get();

                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), what);
                String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                assertTrue(retryAfter != null && retryAfter.matches("[1-9][0-9]*"), what + ", Retry-After: " + retryAfter);
                assertEquals(GRACE_LINE, storedMessages(), what);
                // The group isn't told about a change that wasn't saved.
                verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
            }
        }
    }

    @Test
    void anEditAndADeleteAreSavedWhenS3IsUp() throws Exception {
        assertEquals(HttpStatus.OK, edit().getStatusCode());
        GroupChatMessage saved = new ObjectMapper().readValue(storedMessages(), GroupChatMessage.class);
        assertEquals("Meeting at 3", saved.getMessage());
        assertTrue(saved.isEdited());

        assertEquals(HttpStatus.OK, delete().getStatusCode());
        assertEquals("", storedMessages());
    }

    @Test
    void anEditS3CantReadOrSaveForAMomentAnswers503AndChangesNothing() {
        assertRefusedForNow(this::edit);
    }

    @Test
    void aDeleteS3CantReadOrSaveForAMomentAnswers503AndKeepsTheMessage() {
        assertRefusedForNow(this::delete);
    }

    @Test
    void anEditOrDeleteS3RefusesOrABugHereIsNotOfferedARetry() {
        List<Supplier<ResponseEntity<?>>> changes = List.of(this::edit, this::delete);
        for (Supplier<ResponseEntity<?>> change : changes) {
            for (boolean onRead : List.of(true, false)) {
                for (RuntimeException fault : S3Failures.refusalsAndBugs()) {
                    useFreshStorage();
                    failS3(onRead, fault);
                    String what = (onRead ? "read: " : "upload: ") + fault;

                    ResponseEntity<?> response = change.get();

                    assertFalse(response.getStatusCode().is2xxSuccessful(), what);
                    assertNotEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), what);
                    assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), what);
                    assertEquals(GRACE_LINE, storedMessages(), what);
                    verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
                }
            }
        }
    }

    @Test
    void everyReadOrUploadThatFailsIsLoggedWithTheGroupAndTheCause() {
        Logger logger = (Logger) LoggerFactory.getLogger(GroupChatService.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        try {
            List<RuntimeException> failures = new ArrayList<>(S3Failures.unavailable());
            failures.addAll(S3Failures.refusalsAndBugs());
            for (boolean onRead : List.of(true, false)) {
                for (RuntimeException failure : failures) {
                    useFreshStorage();
                    failS3(onRead, failure);
                    warnings.list.clear();

                    edit();

                    String what = (onRead ? "read: " : "upload: ") + failure;
                    // The route answers some of these without logging, so the service logs each one.
                    assertTrue(warnings.list.stream().anyMatch(warning ->
                            warning.getFormattedMessage().contains("CSA")
                                    && warning.getThrowableProxy() != null
                                    && failure.getClass().getName().equals(warning.getThrowableProxy().getClassName())),
                            what + " was not logged: " + warnings.list);
                }
            }
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }
    }
}
