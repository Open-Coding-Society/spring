package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * The JSON submit routes keep only what the client wrote. The keys a file upload records about
 * the stored file (where it is, who uploaded it, its name, type and size) are the server's: the
 * upload routes set them, and the file download and AI grading read them.
 */
class AssignmentSubmissionContentKeysTest {

    private static final Map<String, Object> SENT = Map.of(
            "type", "link", "url", "https://example.com/project", "notes", "my project",
            "storagePath", "niko/assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf",
            "storedFilename", "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf",
            "uploadedBy", "niko", "filename", "answer.pdf", "contentType", "application/pdf", "size", 2048);
    private static final Map<String, Object> KEPT = Map.of(
            "type", "link", "url", "https://example.com/project", "notes", "my project");

    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private final AssignmentSubmissionHistoryService historyService = mock(AssignmentSubmissionHistoryService.class);
    private final AtomicReference<AssignmentSubmission> saved = new AtomicReference<>();
    private AssignmentSubmissionAPIController controller;

    @BeforeEach
    void setUp() {
        controller = new AssignmentSubmissionAPIController();
        AssignmentJpaRepository assignmentRepo = mock(AssignmentJpaRepository.class);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "groupRepo", mock(GroupsJpaRepository.class));
        ReflectionTestUtils.setField(controller, "submissionRepo", mock(AssignmentSubmissionJPA.class));
        ReflectionTestUtils.setField(controller, "aiGrader", mock(SubmissionAiGrader.class));
        ReflectionTestUtils.setField(controller, "historyService", historyService);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        Person hop = student(1L, "hop");
        when(personRepo.findByUid("hop")).thenReturn(hop);
        when(personRepo.findById(1L)).thenReturn(Optional.of(hop));
        when(personRepo.findByUid("tess")).thenReturn(teacher(2L, "tess"));
        Assignment project = assignment(10L, "project");
        project.setAssignmentType("link");
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(project));
        when(historyService.submit(any(), any())).thenAnswer(invocation -> {
            AssignmentSubmission submission = invocation.getArgument(0);
            saved.set(submission);
            return new AssignmentSubmissionHistoryService.SubmitResult(
                    GradingResult.of(GradingOutcome.CONTENT_UNCHANGED, null), submission, false);
        });
    }

    @Test
    void theSubmitRouteDropsTheServersFileKeys() {
        var request = new AssignmentSubmissionAPIController.SubmissionRequestDto();
        request.submitterId = 1L;
        request.content = SENT;

        controller.submitAssignment(10L, request, userDetails("hop"));

        assertEquals(KEPT, saved.get().getContent());
    }

    @Test
    void theOlderSubmitRouteDropsThemToo() {
        var request = new AssignmentSubmissionAPIController.SubmitAssignmentDto();
        request.submitterId = 1L;
        request.content = SENT;

        controller.submitAssignment(10L, request, userDetails("hop"));

        assertEquals(KEPT, saved.get().getContent());
    }

    /** Teachers skip the author check, but the file keys are still only the upload routes' to set. */
    @Test
    void aTeachersSubmissionDropsThemToo() {
        var request = new AssignmentSubmissionAPIController.SubmissionRequestDto();
        request.submitterId = 1L;
        request.content = SENT;

        controller.submitAssignment(10L, request, userDetails("tess"));

        assertEquals(KEPT, saved.get().getContent());
    }

    private static UserDetails userDetails(String uid) {
        return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
    }
}
