package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * decodeFileOrThrow tells "nothing stored" apart from "couldn't read": a caller that writes
 * back what it read must not take a read that failed for an empty file.
 */
class S3FileHandlerDecodeTest {

    private static final String MESSAGES = "messages-images/messages.jsonl";

    private S3Client s3;
    private S3FileHandler handler;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        handler = new S3FileHandler();
        ReflectionTestUtils.setField(handler, "s3Client", s3);
    }

    @Test
    void aStoredFileComesBackAsBase64() {
        doReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "hello".getBytes(StandardCharsets.UTF_8)))
                .when(s3).getObjectAsBytes(any(GetObjectRequest.class));
        assertEquals("aGVsbG8=", handler.decodeFileOrThrow("CSA", MESSAGES));
    }

    @Test
    void notFoundMeansNothingIsStored() {
        doThrow(NoSuchKeyException.builder().statusCode(404).message("The specified key does not exist.").build())
                .when(s3).getObjectAsBytes(any(GetObjectRequest.class));
        assertNull(handler.decodeFileOrThrow("CSA", MESSAGES));
    }

    @Test
    void anyOtherFailureThrowsInsteadOfReadingAsNothingStored() {
        List<RuntimeException> failures = List.of(
                (S3Exception) S3Exception.builder().statusCode(503).message("Slow Down").build(),
                (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build(),
                SdkClientException.create("Unable to execute HTTP request: Read timed out"));
        for (RuntimeException failure : failures) {
            doThrow(failure).when(s3).getObjectAsBytes(any(GetObjectRequest.class));
            assertSame(failure, assertThrows(RuntimeException.class, () -> handler.decodeFileOrThrow("CSA", MESSAGES)));
            // decodeFile keeps answering null, as its other callers expect.
            assertNull(handler.decodeFile("CSA", MESSAGES));
        }
    }
}
