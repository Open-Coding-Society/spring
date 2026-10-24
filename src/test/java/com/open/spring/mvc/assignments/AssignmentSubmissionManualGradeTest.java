package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * A grade set by a teacher or the assignment creator is final: AI grading never replaces it.
 */
class AssignmentSubmissionManualGradeTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private AssignmentAiGradingService aiGradingService;

    private AssignmentSubmissionAPIController controller;
    private Person creator;
    private AssignmentSubmission ownedSubmission;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "aiGradingService", aiGradingService);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        creator = student(1L, "creator-uid");
        ownedSubmission = submission(100L, assignment(10L, "pilot", creator));

        when(submissionRepo.findById(100L)).thenReturn(Optional.of(ownedSubmission));
        when(submissionRepo.save(any(AssignmentSubmission.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(aiGradingService.grade(any(AssignmentSubmission.class)))
            .thenReturn(AssignmentAiGradingService.GradeResult.graded(3.0, 3, "AI feedback.", false));
        // Mocks skip default methods; run the real one so it reaches the guarded UPDATE below.
        when(submissionRepo.saveAiGradeUnlessManuallyGraded(any(), any())).thenCallRealMethod();
    }

    private UserDetails register(Person person, String... authorities) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        return User.withUsername(person.getUid()).password("ignored").authorities(authorities).build();
    }

    @Test
    void creatorOverridesAnAiGradeAndTheSubmissionIsMarkedManuallyGraded() {
        ownedSubmission.setGrade(3.0);
        ownedSubmission.setFeedback("AI feedback.");

        UserDetails caller = register(creator, "ROLE_STUDENT");
        assertEquals(200, controller.gradeSubmission(100L, caller, 4.0, "Instructor note").getStatusCode().value());

        assertEquals(4.0, ownedSubmission.getGrade());
        assertEquals("Instructor note", ownedSubmission.getFeedback());
        assertTrue(ownedSubmission.hasManualGrade());
    }

    @Test
    void aiRegradeIsRefusedOnceAPersonHasGraded() throws Exception {
        ownedSubmission.setGrade(4.0);
        ownedSubmission.setGradedManually(true);

        UserDetails caller = register(teacher(3L, "mort"), "ROLE_TEACHER");
        assertEquals(409, controller.aiGradeSubmission(100L, caller).getStatusCode().value());

        assertEquals(4.0, ownedSubmission.getGrade());
        verify(aiGradingService, never()).grade(any(AssignmentSubmission.class));
        verify(submissionRepo, never()).applyAiGradeUnlessManuallyGraded(anyLong(), anyDouble(), anyInt(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void aManualGradeSavedWhileTheAiIsStillGradingWins() {
        // The guarded UPDATE matches no row because the creator graded mid-flight.
        when(submissionRepo.applyAiGradeUnlessManuallyGraded(eq(100L), eq(3.0), eq(3), eq("AI feedback."), eq("AI feedback."), eq(false), any())).thenReturn(0);

        UserDetails caller = register(teacher(3L, "mort"), "ROLE_TEACHER");
        assertEquals(409, controller.aiGradeSubmission(100L, caller).getStatusCode().value());

        assertNull(ownedSubmission.getGrade());
        verify(submissionRepo, never()).save(any(AssignmentSubmission.class));
    }

    @Test
    void anUngradedSubmissionStillReceivesTheAiGrade() {
        when(submissionRepo.applyAiGradeUnlessManuallyGraded(eq(100L), eq(3.0), eq(3), eq("AI feedback."), eq("AI feedback."), eq(false), any())).thenReturn(1);

        UserDetails caller = register(teacher(3L, "mort"), "ROLE_TEACHER");
        assertEquals(200, controller.aiGradeSubmission(100L, caller).getStatusCode().value());

        assertEquals(3.0, ownedSubmission.getGrade());
        assertEquals(3, ownedSubmission.getQualityScore());
        assertFalse(ownedSubmission.hasManualGrade());
    }
}
