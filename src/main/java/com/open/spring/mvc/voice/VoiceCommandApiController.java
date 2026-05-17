package com.open.spring.mvc.voice;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/voice")
public class VoiceCommandApiController {

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    private String resolveApiKey() {
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            return openAiApiKey.trim();
        }
        String env = System.getenv("OPENAI_API_KEY");
        return (env == null) ? "" : env.trim();
    }

    @PostMapping(value = "/transcribe-command", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribeCommand(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language) {

        Map<String, Object> response = new HashMap<>();

        if (audio == null || audio.isEmpty()) {
            response.put("text", "");
            response.put("error", "Audio payload is empty");
            return ResponseEntity.badRequest().body(response);
        }

        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            response.put("text", "");
            response.put("error", "Speech service is not configured on the server");
            return ResponseEntity.status(503).body(response);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiKey);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", "whisper-1");
            if (language != null && !language.isBlank()) {
                body.add("language", language.trim());
            }
            body.add("response_format", "json");

            ByteArrayResource audioResource = new ByteArrayResource(audio.getBytes()) {
                @Override
                public String getFilename() {
                    String original = audio.getOriginalFilename();
                    return (original == null || original.isBlank()) ? "command-audio.webm" : original;
                }
            };
            body.add("file", audioResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            RestTemplate restTemplate = new RestTemplate();

            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> sttResponse = restTemplate.postForEntity(
                    "https://api.openai.com/v1/audio/transcriptions",
                    requestEntity,
                    Map.class);

            Object textObj = (sttResponse.getBody() != null) ? sttResponse.getBody().get("text") : null;
            String text = (textObj == null) ? "" : String.valueOf(textObj).trim();

            response.put("text", text);
            return ResponseEntity.ok(response);
        } catch (IOException io) {
            response.put("text", "");
            response.put("error", "Unable to read audio payload");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("text", "");
            response.put("error", "Speech transcription failed");
            return ResponseEntity.status(502).body(response);
        }
    }
}
