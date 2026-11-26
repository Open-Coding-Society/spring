package com.open.spring.mvc.S3uploads;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.S3uploads.UserFileAccess.Answer;

/**
 * Files in S3 by folder and name. The folder in a request says nothing about who is asking, so
 * every route asks {@link UserFileAccess} first and touches storage only when it's allowed.
 */
@RestController
@RequestMapping("/api/files")
public class S3FileApiController {

    private final FileHandler fileHandler;
    private final UserFileAccess fileAccess;

    public S3FileApiController(FileHandler fileHandler, UserFileAccess fileAccess) {
        this.fileHandler = fileHandler;
        this.fileAccess = fileAccess;
    }

    /**
     * Upload a file to S3
     * @param uid User ID
     * @param filename Name of the file
     * @param base64Data Base64 encoded file content
     * @return Response with filename or error
     */
    @PostMapping("/upload/{uid}")
    public ResponseEntity<?> uploadFile(
            @PathVariable String uid,
            @RequestParam String filename,
            @RequestBody String base64Data,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || fileAccess.upload(userDetails.getUsername(), uid, filename) != Answer.ALLOWED) {
            return new ResponseEntity<>("You can't upload this file here", HttpStatus.FORBIDDEN);
        }

        try {
            String result = fileHandler.uploadFile(base64Data, filename, uid);
            
            if (result != null) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "File uploaded successfully");
                response.put("filename", result);
                response.put("uid", uid);
                return new ResponseEntity<>(response, HttpStatus.OK);
            } else {
                return new ResponseEntity<>("Upload failed", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("Error uploading file: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Download a file from S3
     * @param uid User ID
     * @param filename Name of the file to download
     * @return Base64 encoded file content
     */
    @GetMapping("/download/{uid}/{filename}")
    public ResponseEntity<?> downloadFile(
            @PathVariable String uid,
            @PathVariable String filename,
            @AuthenticationPrincipal UserDetails userDetails) {
        return downloadFileByQuery(uid, filename, userDetails);
        }

        /**
         * Download a file from S3 using query parameters so nested keys survive URL parsing.
         * @param uid User ID
         * @param filename File key inside the user's S3 prefix
         * @return File bytes as a downloadable/inline response
         */
        @GetMapping("/download")
        public ResponseEntity<?> downloadFileByQuery(
            @RequestParam String uid,
            @RequestParam String filename,
            @AuthenticationPrincipal UserDetails userDetails) {
        Answer answer = userDetails == null ? Answer.FORBIDDEN : fileAccess.download(userDetails.getUsername(), uid, filename);
        if (answer == Answer.NOT_FOUND) {
            return new ResponseEntity<>("File not found", HttpStatus.NOT_FOUND);
        }
        if (answer != Answer.ALLOWED) {
            return new ResponseEntity<>("You can't download this file", HttpStatus.FORBIDDEN);
        }

        try {
            String base64Data = fileHandler.decodeFile(uid, filename);
            
            if (base64Data != null) {
                    byte[] data = java.util.Base64.getDecoder().decode(base64Data);
                    ByteArrayResource resource = new ByteArrayResource(data);

                    // A handed-in file opens on this site, so only a type that can't run anything
                    // opens in the browser. Everything else downloads as plain bytes.
                    MediaType mediaType = org.springframework.http.MediaTypeFactory.getMediaType(filename)
                            .filter(S3FileApiController::opensInTheBrowser)
                            .orElse(MediaType.APPLICATION_OCTET_STREAM);
                    ContentDisposition.Builder disposition = MediaType.APPLICATION_OCTET_STREAM.equals(mediaType)
                            ? ContentDisposition.attachment()
                            : ContentDisposition.inline();

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentDisposition(disposition
                            .filename(filename, StandardCharsets.UTF_8)
                            .build());

                    return ResponseEntity.ok()
                            .headers(headers)
                            .contentType(mediaType)
                            .contentLength(data.length)
                            .body(resource);
            } else {
                return new ResponseEntity<>("File not found", HttpStatus.NOT_FOUND);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("Error downloading file: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Delete all files for a user
     * @param uid User ID
     * @return Success or failure message
     */
    @DeleteMapping("/delete/{uid}")
    public ResponseEntity<?> deleteUserFiles(@PathVariable String uid,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || fileAccess.deleteFolder(userDetails.getUsername(), uid) != Answer.ALLOWED) {
            return new ResponseEntity<>("You can't delete these files", HttpStatus.FORBIDDEN);
        }

        try {
            boolean result = fileHandler.deleteFiles(uid);
            
            if (result) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Files deleted successfully");
                response.put("uid", uid);
                return new ResponseEntity<>(response, HttpStatus.OK);
            } else {
                return new ResponseEntity<>("Delete failed", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("Error deleting files: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /** PDFs, plain images and plain text. HTML, SVG and scripts never open on this site. */
    private static boolean opensInTheBrowser(MediaType type) {
        return MediaType.APPLICATION_PDF.equals(type) || MediaType.IMAGE_PNG.equals(type)
                || MediaType.IMAGE_JPEG.equals(type) || MediaType.IMAGE_GIF.equals(type)
                || MediaType.TEXT_PLAIN.equals(type);
    }
}
