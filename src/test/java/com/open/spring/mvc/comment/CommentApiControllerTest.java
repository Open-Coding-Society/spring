package com.open.spring.mvc.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.slack.CalendarIssue;
import com.open.spring.mvc.slack.CalendarIssueService;
import com.open.spring.mvc.slack.EmailNotificationService;

public class CommentApiControllerTest {

    @Mock
    private CommentJPA commentJPA;

    @Mock
    private CalendarIssueService calendarIssueService;

    @Mock
    private EmailNotificationService emailNotificationService;

    private CommentApiController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new CommentApiController(commentJPA);
        ReflectionTestUtils.setField(controller, "calendarIssueService", calendarIssueService);
        ReflectionTestUtils.setField(controller, "emailNotificationService", emailNotificationService);
    }

    @Test
    public void createIssueCommentSavesCommentAndNotifiesOwner() {
        CalendarIssue issue = new CalendarIssue();
        issue.setTitle("Need reply");
        issue.setOwnerUid("bob");

        Comment saved = new Comment("issue-12", "Looks good", "alice");
        when(calendarIssueService.getIssueById(12L, "alice", false)).thenReturn(Optional.of(issue));
        when(commentJPA.save(any(Comment.class))).thenReturn(saved);

        UserDetails userDetails = User.withUsername("alice").password("ignored").authorities("ROLE_USER").build();
        ResponseEntity<?> response = controller.createIssueComment(12L, Map.of("text", "Looks good"), userDetails);

        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(emailNotificationService).notifyOnIssueComment(issue, saved);
    }

    @Test
    public void toggleIssueStarAddsStarComment() {
        CalendarIssue issue = new CalendarIssue();
        issue.setTitle("Need reply");
        issue.setOwnerUid("bob");

        when(calendarIssueService.getIssueById(12L, "alice", false)).thenReturn(Optional.of(issue));
        when(commentJPA.existsByAssignmentAndAuthor("issue-12::star", "alice")).thenReturn(false);
        when(commentJPA.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentJPA.countByAssignment("issue-12::star")).thenReturn(1L);

        UserDetails userDetails = User.withUsername("alice").password("ignored").authorities("ROLE_USER").build();
        ResponseEntity<?> response = controller.toggleIssueStar(12L, userDetails);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(Boolean.TRUE, body.get("starred"));
        assertEquals(1L, body.get("starCount"));
    }

    @Test
    public void getCommentsByIssueReturnsCommentsForAuthenticatedUser() {
        Comment comment = new Comment("issue-12", "Looks good", "alice");
        when(commentJPA.findByAssignmentOrderByTimestampDesc("issue-12")).thenReturn(List.of(comment));

        UserDetails userDetails = User.withUsername("alice").password("ignored").authorities("ROLE_USER").build();
        ResponseEntity<List<Comment>> response = controller.getCommentsByIssue(12L, userDetails);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }
}