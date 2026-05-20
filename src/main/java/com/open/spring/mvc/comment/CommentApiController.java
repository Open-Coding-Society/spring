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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CrossOrigin;

import com.open.spring.mvc.slack.CalendarIssueService;
import com.open.spring.mvc.slack.EmailNotificationService;

@RestController
@RequestMapping("/api/Comment")
@CrossOrigin(origins = { "http://127.0.0.1:4500", "https://pages.opencodingsociety.com" }, allowCredentials = "true")
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
                    emailNotificationService.notifyAllStarredIssueFollowers(issue, savedComment);

                    Map<String, Object> response = new HashMap<>();
                    response.put("comment", savedComment);
                    response.put("commentCount", CommentJPA.countByAssignment(issueAssignmentKey(issueId)));
                    return ResponseEntity.status(HttpStatus.CREATED).body(response);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    /**
     * Reply to a specific comment (nested comment/thread)
     * @param parentCommentId - ID of the comment to reply to
     * @param payload - Contains the reply text
     * @param userDetails - Authenticated user
     * @return ResponseEntity with the saved reply
     */
    @PostMapping("/reply/{parentCommentId}")
    public ResponseEntity<?> replyToComment(@PathVariable Long parentCommentId,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        var parentComment = CommentJPA.findById(parentCommentId);
        if (parentComment.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Parent comment not found"));
        }

        String text = payload.get("text") == null ? "" : String.valueOf(payload.get("text")).trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Reply text is required"));
        }

        String authorUid = userDetails.getUsername();
        Comment parent = parentComment.get();
        Comment reply = new Comment(parent.getAssignment(), text, authorUid);
        reply.setParentCommentId(parentCommentId);
        Comment savedReply = CommentJPA.save(reply);

        Map<String, Object> response = new HashMap<>();
        response.put("reply", savedReply);
        response.put("replyCount", CommentJPA.findByParentCommentId(parentCommentId).size());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all replies to a specific comment
     * @param parentCommentId - ID of the parent comment
     * @return ResponseEntity with list of replies
     */
    @GetMapping("/replies/{parentCommentId}")
    public ResponseEntity<List<Comment>> getReplies(@PathVariable Long parentCommentId) {
        List<Comment> replies = CommentJPA.findByParentCommentIdOrderByTimestampDesc(parentCommentId);
        return new ResponseEntity<>(replies, HttpStatus.OK);
    }

    /**
     * Search comments by query (text or author) for a specific issue
     * @param issueId - Issue ID to search within
     * @param query - Search query (text or author name)
     * @return ResponseEntity with matching comments
     */
    @GetMapping("/issue/{issueId}/search")
    public ResponseEntity<List<Comment>> searchCommentsInIssue(
            @PathVariable Long issueId,
            @RequestParam String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        
        List<Comment> results = CommentJPA.searchCommentsByAssignment(issueAssignmentKey(issueId), query);
        return new ResponseEntity<>(results, HttpStatus.OK);
    }

    /**
     * Search all comments globally
     * @param query - Search query (text or author name)
     * @return ResponseEntity with matching comments
     */
    @GetMapping("/search")
    public ResponseEntity<List<Comment>> searchAllComments(
            @RequestParam String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        
        List<Comment> results = CommentJPA.searchAllComments(query);
        return new ResponseEntity<>(results, HttpStatus.OK);
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
                    boolean starLocked = calendarIssueService.isIssueAssignedToUserGroups(issue, authorUid);
                    boolean starred = false;

                    // Find existing star comments for this assignment with text "star"
                    List<Comment> existingStars = CommentJPA.findByAssignmentAndText(starKey, "star");
                    Comment toRemove = null;
                    if (existingStars != null) {
                        for (Comment c : existingStars) {
                            if (authorUid.equals(c.getAuthor())) {
                                toRemove = c;
                                break;
                            }
                        }
                    }

                    if (toRemove != null) {
                        if (starLocked) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("message", "This issue is auto-starred because it is assigned to one of your groups."));
                        }
                        CommentJPA.delete(toRemove);
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

    /**
     * Ensure the current user has a star for the given issue (create if missing, but do not remove)
     */
    @PostMapping("/issue/{issueId}/star/ensure")
    public ResponseEntity<?> ensureIssueStar(@PathVariable Long issueId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        return calendarIssueService.getIssueById(issueId, userDetails.getUsername(), hasPrivilegedRole(userDetails))
                .<ResponseEntity<?>>map(issue -> {
                    String starKey = issueStarAssignmentKey(issueId);
                    String authorUid = userDetails.getUsername();

                    // If a star by this author already exists, return current state
                    boolean exists = CommentJPA.existsByAssignmentAndAuthor(starKey, authorUid);
                    if (!exists) {
                        CommentJPA.save(new Comment(starKey, "star", authorUid));
                    }

                    Map<String, Object> response = new HashMap<>();
                    response.put("starred", true);
                    response.put("starCount", CommentJPA.countByAssignment(starKey));
                    response.put("issueId", issueId);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    /**
     * DELETE endpoint to remove a comment by ID
     * Only the comment author or privileged users can delete
     * Also cascades delete to all nested replies (comments with this as parent)
     * @param commentId - ID of the comment to delete
     * @param userDetails - Authenticated user
     * @return ResponseEntity with success/error message
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable Long commentId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        var comment = CommentJPA.findById(commentId);
        if (comment.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Comment not found"));
        }

        Comment toDelete = comment.get();
        String authorUid = userDetails.getUsername();
        boolean isPrivileged = hasPrivilegedRole(userDetails);

        // Only allow deletion by comment author or privileged users
        if (!toDelete.getAuthor().equals(authorUid) && !isPrivileged) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only the comment author or an administrator can delete this comment"));
        }

        // Cascade delete all nested replies to this comment
        List<Comment> replies = CommentJPA.findByParentCommentId(commentId);
        if (replies != null && !replies.isEmpty()) {
            CommentJPA.deleteAll(replies);
        }

        // Then delete the comment itself
        CommentJPA.delete(toDelete);
        return ResponseEntity.ok(Map.of("success", true, "message", "Comment and all replies deleted"));
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
