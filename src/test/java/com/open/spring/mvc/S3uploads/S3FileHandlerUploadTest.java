package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * uploadFileOrThrow tells a stored file apart from a write that failed: it throws the failure
 * where uploadFile answers null, so a caller can tell S3 being unavailable for a moment from a
 * request S3 refused.
 */
class S3FileHandlerUploadTest {

    private static final String HELLO = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

    private S3Client s3;
    private S3FileHandler handler;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        handler = new S3FileHandler();
        ReflectionTestUtils.setField(handler, "s3Client", s3);
        ReflectionTestUtils.setField(handler, "bucketName", "pages-assignments");
    }

    @Test
    void aWriteS3TakesStoresTheBytesUnderTheGroupsKey() throws Exception {
        assertEquals("shared-files/notes.md", handler.uploadFileOrThrow(HELLO, "shared-files/notes.md", "CSA"));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals("pages-assignments", request.getValue().bucket());
        assertEquals("CSA/shared-files/notes.md", request.getValue().key());
        try (InputStream bytes = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), bytes.readAllBytes());
        }
    }

    @Test
    void aWriteThatFailsThrowsTheFailureInsteadOfAnsweringNull() {
        S3Exception throttled = (S3Exception) S3Exception.builder().statusCode(503).message("Slow Down").build();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(throttled);

        assertSame(throttled, assertThrows(S3Exception.class,
                () -> handler.uploadFileOrThrow(HELLO, "shared-files/notes.md", "CSA")));
        // uploadFile keeps answering null, as its other callers expect.
        assertNull(handler.uploadFile(HELLO, "shared-files/notes.md", "CSA"));
    }

    @Test
    void withoutS3ItThrowsInsteadOfAnsweringNull() {
        S3FileHandler notConfigured = new S3FileHandler();

        assertThrows(IllegalStateException.class,
                () -> notConfigured.uploadFileOrThrow(HELLO, "shared-files/notes.md", "CSA"));
    }
}
