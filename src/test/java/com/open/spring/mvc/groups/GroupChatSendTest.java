package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Sending a message reads the group's whole messages file, adds the message and writes the
 * file back. A read that failed must not count as an empty chat: writing that back would
 * replace the whole history with the one new message.
 */
class GroupChatSendTest {

    private static final String MESSAGES_KEY = "CSA/messages-images/messages.jsonl";
    private static final String ADA_LINE = "{\"id\":\"m1\",\"author\":\"Ada Lovelace\",\"author_uid\":\"ada\","
            + "\"message\":\"Meeting at 3\",\"date\":\"2026-11-21T10:00:00Z\",\"image\":null,\"edited\":false}";

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

    /** An empty bucket behind a real S3FileHandler, as S3 answers: a missing key is a 404 NoSuchKey. */
    private void useFreshStorage() {
        objects.clear();
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
        wire(storage);
    }

    /** The send route over this storage, with Grace Hopper signed in. */
    private void wire(S3FileHandler storage) {
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

    private ResponseEntity<?> send(String text) {
        GroupChatMessage request = new GroupChatMessage();
        request.setMessage(text);
        return controller.postMessage(7L, request);
    }

    private String storedMessages() {
        byte[] bytes = objects.get(MESSAGES_KEY);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aReadThatFailsRefusesTheSendWithA503AndKeepsTheHistory() {
        // S3 throttled (503 Slow Down), out of reach, or timing out the request (its RequestTimeout
        // comes as a 400): failures that clear if you wait.
        List<RuntimeException> failures = List.of(
                (S3Exception) S3Exception.builder().statusCode(503).message("Slow Down").build(),
                SdkClientException.create("Unable to execute HTTP request: Read timed out"),
                (S3Exception) S3Exception.builder().statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestTimeout").build())
                        .message("Your socket connection to the server was not read from or written to within the timeout period.")
                        .build());
        for (RuntimeException failure : failures) {
            useFreshStorage();
            objects.put(MESSAGES_KEY, ADA_LINE.getBytes(StandardCharsets.UTF_8));
            doThrow(failure).when(s3).getObjectAsBytes(any(GetObjectRequest.class));

            ResponseEntity<?> response = send("Works for me");

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), failure.toString());
            String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            assertTrue(retryAfter != null && retryAfter.matches("[1-9][0-9]*"), "Retry-After: " + retryAfter);
            assertEquals(ADA_LINE, storedMessages(), failure.toString());
            // The group isn't told about a message that wasn't saved.
            verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
        }
    }

    @Test
    void aReadS3RefusesOrABugHereIsNotOfferedARetryAndWritesNothing() {
        // No permission, a bad key, or a fault in this server: waiting won't fix these.
        List<RuntimeException> faults = List.of(
                (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build(),
                new NullPointerException("a bug here, not S3"));
        for (RuntimeException fault : faults) {
            useFreshStorage();
            objects.put(MESSAGES_KEY, ADA_LINE.getBytes(StandardCharsets.UTF_8));
            doThrow(fault).when(s3).getObjectAsBytes(any(GetObjectRequest.class));

            ResponseEntity<?> response = send("Works for me");

            assertFalse(response.getStatusCode().is2xxSuccessful(), fault.toString());
            assertNotEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), fault.toString());
            assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), fault.toString());
            assertEquals(ADA_LINE, storedMessages(), fault.toString());
            verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
        }
    }

    @Test
    void aGroupWithNoMessagesFileYetStartsItsHistoryWithTheSend() throws Exception {
        // S3's 404: nothing is stored yet, so there's no history to lose.
        ResponseEntity<?> response = send("Works for me");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> lines = storedMessages().lines().toList();
        assertEquals(1, lines.size(), storedMessages());
        GroupChatMessage saved = new ObjectMapper().readValue(lines.get(0), GroupChatMessage.class);
        assertEquals("Works for me", saved.getMessage());
        assertEquals("grace", saved.getAuthor_uid());
    }

    @Test
    void withoutS3SettingsASendIsStillBroadcastLive() {
        // A local server without S3 saves nothing, but its chats keep working live.
        wire(new S3FileHandler());

        ResponseEntity<?> response = send("Works for me");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/group/7"), frame.capture());
        assertEquals("Works for me", ((GroupChatEvent) frame.getValue()).getMessage());
    }
}
