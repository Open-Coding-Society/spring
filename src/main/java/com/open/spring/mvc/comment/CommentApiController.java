package com.open.spring.mvc.comment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

import com.open.spring.mvc.slack.CalendarIssueService;
import com.open.spring.mvc.slack.EmailNotificationService;

@RestController
@RequestMapping("/api/Comment")
public class CommentApiController {

    @Autowired
    private final CommentJPA CommentJPA;

    @Autowired
    private CalendarIssueService calendarIssueService;

    @Autowired
    private EmailNotificationService emailNotificationService;

    // Constructor injection for CommentJPA
    public CommentApiController(CommentJPA CommentJPA) {
        this.CommentJPA = CommentJPA;
    }

    /**
     * Endpoint to create a new comment
     * @param comment - The Comment object received as JSON
     * @return ResponseEntity with the saved Comment and HTTP status CREATED
     */
    @PostMapping("/create")
    public ResponseEntity<Comment> createComment(@RequestBody Comment comment) {
        Comment savedComment = CommentJPA.save(comment);
        return new ResponseEntity<>(savedComment, HttpStatus.CREATED);
    }

    /**
     * Endpoint to retrieve all comments
     * @return ResponseEntity with a list of all comments and HTTP status OK
     */
    @GetMapping("/all")
    public ResponseEntity<List<Comment>> getAllComments() {
        List<Comment> comments = CommentJPA.findAll();
        return new ResponseEntity<>(comments, HttpStatus.OK);
    }

    /**
     * Endpoint to retrieve a comment by its ID
     * @param id - ID of the comment to retrieve
     * @return ResponseEntity with the comment if found, or NOT FOUND status
     */
    @GetMapping("/{id}")
    public ResponseEntity<Comment> getCommentById(@PathVariable Long id) {
        return CommentJPA.findById(id)
                .map(comment -> new ResponseEntity<>(comment, HttpStatus.OK))
                .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    /**
     * Endpoint to retrieve comments by assignment
     * @param assignment - The assignment name to filter comments by
     * @return ResponseEntity with the list of comments if found, or NOT FOUND status
     */
    @GetMapping("/by-assignment")
    public ResponseEntity<List<Comment>> getCommentsByAssignment(@RequestParam String assignment) {
        List<Comment> comments = CommentJPA.findByAssignment(assignment);
        
        if (comments.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);  // Return 404 if no comments found
        }
        
        return new ResponseEntity<>(comments, HttpStatus.OK);  // Return 200 with the list of comments
    }

    /**
     * Endpoint to retrieve comments by author
     * @param author - The author's name to filter comments by
     * @return ResponseEntity with the list of comments if found, or NOT FOUND status
     */
    @GetMapping("/by-author")
    public ResponseEntity<List<Comment>> getCommentsByAuthor(@RequestParam String author) {
        List<Comment> comments = CommentJPA.findByAuthor(author);
        
        if (comments.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);  // Return 404 if no comments found
        }
        
        return new ResponseEntity<>(comments, HttpStatus.OK);  // Return 200 with the list of comments
    }

    @GetMapping("/issue/{issueId}")
    public ResponseEntity<List<Comment>> getCommentsByIssue(@PathVariable Long issueId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        List<Comment> comments = CommentJPA.findByAssignmentOrderByTimestampDesc(issueAssignmentKey(issueId));
        return new ResponseEntity<>(comments, HttpStatus.OK);
    }

    @PostMapping("/issue/{issueId}")
    public ResponseEntity<?> createIssueComment(@PathVariable Long issueId,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        String text = payload.get("text") == null ? "" : String.valueOf(payload.get("text")).trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Comment text is required"));
        }

        return calendarIssueService.getIssueById(issueId, userDetails.getUsername(), hasPrivilegedRole(userDetails))
                .<ResponseEntity<?>>map(issue -> {
                    String authorUid = userDetails.getUsername();
                    Comment savedComment = CommentJPA.save(new Comment(issueAssignmentKey(issueId), text, authorUid));
                    emailNotificationService.notifyOnIssueComment(issue, savedComment);

                    Map<String, Object> response = new HashMap<>();
                    response.put("comment", savedComment);
                    response.put("commentCount", CommentJPA.countByAssignment(issueAssignmentKey(issueId)));
                    return ResponseEntity.status(HttpStatus.CREATED).body(response);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    @PostMapping("/issue/{issueId}/star")
    public ResponseEntity<?> toggleIssueStar(@PathVariable Long issueId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        return calendarIssueService.getIssueById(issueId, userDetails.getUsername(), hasPrivilegedRole(userDetails))
                .<ResponseEntity<?>>map(issue -> {
                    String starKey = issueStarAssignmentKey(issueId);
                    String authorUid = userDetails.getUsername();
                    boolean starred;
                    if (CommentJPA.existsByAssignmentAndAuthor(starKey, authorUid)) {
                        CommentJPA.deleteByAssignmentAndAuthor(starKey, authorUid);
                        starred = false;
                    } else {
                        CommentJPA.save(new Comment(starKey, "star", authorUid));
                        starred = true;
                    }

                    Map<String, Object> response = new HashMap<>();
                    response.put("starred", starred);
                    response.put("starCount", CommentJPA.countByAssignment(starKey));
                    response.put("issueId", issueId);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    private String issueAssignmentKey(Long issueId) {
        return "issue-" + issueId;
    }

    private String issueStarAssignmentKey(Long issueId) {
        return issueAssignmentKey(issueId) + "::star";
    }

    private boolean hasPrivilegedRole(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_TEACHER".equals(role));
    }
}
