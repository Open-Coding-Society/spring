package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;

import com.open.spring.mvc.S3uploads.UserFileAccess.Answer;

/**
 * A handed-in file is served from this site, so a browser must never run anything in it. Only
 * types that can't carry script open in the browser, and everything else downloads as bytes.
 */
class S3FileApiControllerDownloadTypeTest {

    private static final String FOLDER = "hop";

    private final FileHandler files = mock(FileHandler.class);
    private final UserFileAccess access = mock(UserFileAccess.class);
    private final S3FileApiController controller = new S3FileApiController(files, access);

    @ParameterizedTest
    @ValueSource(strings = {"page.html", "page.htm", "drawing.svg", "script.js", "page.xhtml", "feed.xml", "data.bin",
            "noextension"})
    void filesThatCouldRunScriptDownloadAsBytes(String name) {
        ResponseEntity<?> response = download(name);

        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment(), name);
    }

    @ParameterizedTest
    @CsvSource({"answer.pdf, application/pdf", "photo.png, image/png", "photo.jpg, image/jpeg", "loop.gif, image/gif",
            "notes.txt, text/plain"})
    void pdfsImagesAndPlainTextStillOpenInTheBrowser(String name, String type) {
        ResponseEntity<?> response = download(name);

        assertEquals(MediaType.parseMediaType(type), response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentDisposition().isInline(), name);
    }

    private ResponseEntity<?> download(String name) {
        String filename = "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_" + name;
        when(access.download("tess", FOLDER, filename)).thenReturn(Answer.ALLOWED);
        when(files.decodeFile(FOLDER, filename))
                .thenReturn(Base64.getEncoder().encodeToString("<p>handed in</p>".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<?> response = controller.downloadFileByQuery(FOLDER, filename,
                User.withUsername("tess").password("unused").authorities("ROLE_TEACHER").build());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response;
    }
}
