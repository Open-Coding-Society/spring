package com.nighthawk.spring_portfolio.mvc.messages;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.person.PersonRoleJpaRepository;

@RestController
@RequestMapping("/api/sagai/comments")
@CrossOrigin(origins = {"http://127.0.0.1:4100","https://nighthawkcoders.github.io/portfolio_2025/"}, allowCredentials = "true")
public class SagaiCommentApiController {

     private static final Logger logger = LoggerFactory.getLogger(SagaiCommentApiController.class);
    @Autowired
    private SagaiCommentJpaRepository commentRepository;

    @Autowired
    private SagaiMessageJpaRepository messageRepository;

      @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private PersonRoleJpaRepository personRoleRepository; // For role lookup

    @GetMapping
    public List<SagaiComment> getAllComments() {
        return commentRepository.findAll();
    }

    @PostMapping("/{messageId}")
    public ResponseEntity<SagaiComment> createComment(@PathVariable Long messageId, @RequestBody SagaiComment comment) {
        return messageRepository.findById(messageId).map(message -> {
            comment.setMessage(message);
            return ResponseEntity.ok(commentRepository.save(comment));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
        if (commentRepository.existsById(id)) {
            logger.info("Comment exists, attempting to delete...");
            commentRepository.deleteById(id);
            logger.info("Comment deleted successfully.");
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }


    // @DeleteMapping("/{id}")
    // public ResponseEntity<Void> deleteComment(@PathVariable Long id, @RequestHeader("username") String username) {
    //     // Find the person making the request
    //     PersonRole personRole = personRoleRepository.findByName(roleName)
    //             .orElseThrow(() -> new RuntimeException("User not found"));

    //     // Check the user's role
    //     if (!"ROLE_ADMIN".equals(personRole.getRole())) {
    //         logger.warn("Unauthorized attempt to delete comment by user: " + username);
    //         return ResponseEntity.status(403).build(); // Forbidden
    //     }

    //     // Allow deletion if the user is an admin
    //     if (commentRepository.existsById(id)) {
    //         logger.info("Comment exists, attempting to delete...");
    //         commentRepository.deleteById(id);
    //         logger.info("Comment deleted successfully.");
    //         return ResponseEntity.ok().build();
    //     }
    //     return ResponseEntity.notFound().build();
    // }
//     @DeleteMapping("/{id}")
// public ResponseEntity<Void> deleteComment(@PathVariable Long id, @RequestHeader("username") String username) {
//     // Look up the user by username to determine their role
//     Person person = personRepository.findByEmail(username); // You need a method like this in your repository

//     if (person == null) {
//         logger.warn("User not found: " + username);
//         return ResponseEntity.status(404).build(); // Not Found
//     }

//     PersonRole personRole = person.getRoles().stream()
//         .filter(role -> "ROLE_ADMIN".equals(role.getName()))
//         .findFirst()
//         .orElse(null); // If no admin role is found, personRole will be null

//     if (personRole == null) {
//         logger.warn("Unauthorized attempt to delete comment by user: " + username);
//         return ResponseEntity.status(403).build(); // Forbidden
//     }

//     // Allow deletion if the user is an admin
//     if (commentRepository.existsById(id)) {
//         logger.info("Comment exists, attempting to delete...");
//         commentRepository.deleteById(id);
//         logger.info("Comment deleted successfully.");
//         return ResponseEntity.ok().build();
//     }

//     return ResponseEntity.notFound().build();
// }

}
