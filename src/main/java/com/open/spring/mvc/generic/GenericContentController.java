package com.open.spring.mvc.generic;

import com.open.spring.mvc.identity.User;
import com.open.spring.mvc.identity.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/content")
public class GenericContentController {

    @Autowired
    private UserContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/{type}")
    public ResponseEntity<UserContent> createContent(
            @PathVariable ContentType type,
            @RequestBody UserContent content,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails != null) {
            String email = userDetails.getUsername();
            Optional<User> author = userRepository.findByEmail(email);

            if (author.isPresent()) {
                content.setAuthor(author.get());
            }
        }

        content.setType(type);
        UserContent saved = contentRepository.save(content);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @GetMapping("/{type}")
    public ResponseEntity<List<UserContent>> listContent(@PathVariable ContentType type) {
        return new ResponseEntity<>(contentRepository.findByType(type), HttpStatus.OK);
    }

    @GetMapping("/{type}/{authorId}")
    public ResponseEntity<List<UserContent>> listContentByAuthor(@PathVariable ContentType type,
            @PathVariable Long authorId) {
        return new ResponseEntity<>(contentRepository.findByAuthorIdAndType(authorId, type), HttpStatus.OK);
    }

    @GetMapping("/id/{id}")
    public ResponseEntity<UserContent> getContent(@PathVariable Long id) {
        return contentRepository.findById(id)
                .map(content -> new ResponseEntity<>(content, HttpStatus.OK))
                .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContent(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        Optional<UserContent> content = contentRepository.findById(id);
        if (content.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // Simple owner check
        // Note: Real world would need more robust permissions (admin vs owner)
        String email = userDetails.getUsername();
        if (!content.get().getAuthor().getEmail().equals(email)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        contentRepository.deleteById(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
