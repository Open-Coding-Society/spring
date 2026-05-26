package com.open.spring.mvc.email;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Email.Email;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/email")
public class EmailApiController {

    @PostMapping("/send")
    public ResponseEntity<?> sendEmail(@Valid @RequestBody SendEmailRequest request,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        String caller = userDetails != null ? userDetails.getUsername() : "unknown";
        System.out.println("[EmailApi] /api/email/send invoked by " + caller
                + " -> " + request.getTo().size() + " recipient(s)");

        Map<String, String> results;
        try {
            results = Email.sendEmailViaSmtp(request.getTo(), request.getSubject(), request.getBody());
        } catch (IllegalStateException ex) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "SMTP not configured");
            error.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }

        long sent = results.values().stream().filter("sent"::equals).count();
        Map<String, Object> response = new HashMap<>();
        response.put("sent", sent);
        response.put("total", results.size());
        response.put("results", results);

        if (sent == 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
