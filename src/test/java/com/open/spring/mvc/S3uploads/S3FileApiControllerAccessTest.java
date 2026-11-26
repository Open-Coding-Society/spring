package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import com.open.spring.mvc.S3uploads.UserFileAccess.Answer;

/**
 * /api/files asks {@link UserFileAccess} before it touches storage, and never reads, writes or
 * deletes a file it was refused.
 */
class S3FileApiControllerAccessTest {

    private static final String FILE = "assignment-submissions/frq-1/1764000000000_6a1f0b2c_answer.pdf";

    private final FileHandler files = mock(FileHandler.class);
    private final UserFileAccess access = mock(UserFileAccess.class);
    private final S3FileApiController controller = new S3FileApiController(files, access);

    @Test
    void anAllowedDownloadReturnsTheFile() {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        when(access.download("gus", "hop", FILE)).thenReturn(Answer.ALLOWED);
        when(files.decodeFile("hop", FILE)).thenReturn(Base64.getEncoder().encodeToString(pdf));

        ResponseEntity<?> response = controller.downloadFileByQuery("hop", FILE, signedIn("gus"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(pdf, ((ByteArrayResource) response.getBody()).getByteArray());
    }

    @Test
    void aRefusedDownloadNeverReadsStorage() {
        when(access.download("niko", "hop", FILE)).thenReturn(Answer.FORBIDDEN);
        when(access.download("niko", "Sprinters", "messages-images/messages.jsonl")).thenReturn(Answer.NOT_FOUND);

        assertEquals(HttpStatus.FORBIDDEN, controller.downloadFileByQuery("hop", FILE, signedIn("niko")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller.downloadFileByQuery("Sprinters", "messages-images/messages.jsonl", signedIn("niko")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.downloadFile("hop", "notes.txt", null).getStatusCode());
        verify(files, never()).decodeFile(anyString(), anyString());
    }

    @Test
    void thePathFormAsksTheSameQuestion() {
        when(access.download("hop", "hop", "notes.txt")).thenReturn(Answer.NOT_FOUND);

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadFile("hop", "notes.txt", signedIn("hop")).getStatusCode());
        verify(access).download("hop", "hop", "notes.txt");
    }

    @Test
    void anAllowedUploadIsStored() {
        when(access.upload("hop", "hop", "notes/todo.txt")).thenReturn(Answer.ALLOWED);
        when(files.uploadFile("dG9kbw==", "notes/todo.txt", "hop")).thenReturn("notes/todo.txt");

        assertEquals(HttpStatus.OK, controller.uploadFile("hop", "notes/todo.txt", "dG9kbw==", signedIn("hop")).getStatusCode());
    }

    @Test
    void aRefusedUploadNeverWritesStorage() {
        when(access.upload("niko", "hop", FILE)).thenReturn(Answer.FORBIDDEN);

        assertEquals(HttpStatus.FORBIDDEN, controller.uploadFile("hop", FILE, "ZmFrZQ==", signedIn("niko")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.uploadFile("hop", FILE, "ZmFrZQ==", null).getStatusCode());
        verify(files, never()).uploadFile(anyString(), anyString(), anyString());
    }

    @Test
    void anAllowedDeleteRemovesTheFolder() {
        when(access.deleteFolder("root", "hop")).thenReturn(Answer.ALLOWED);
        when(files.deleteFiles("hop")).thenReturn(true);

        assertEquals(HttpStatus.OK, controller.deleteUserFiles("hop", signedIn("root")).getStatusCode());
    }

    @Test
    void aRefusedDeleteNeverTouchesStorage() {
        when(access.deleteFolder(any(), anyString())).thenReturn(Answer.FORBIDDEN);

        assertEquals(HttpStatus.FORBIDDEN, controller.deleteUserFiles("Sprinters", signedIn("root")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.deleteUserFiles("hop", null).getStatusCode());
        verify(files, never()).deleteFiles(anyString());
    }

    private static UserDetails signedIn(String uid) {
        return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
    }
}
