package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Who may choose a submission's graders (POST /api/submissions/{id}/assigned-graders): teachers,
 * admins and the assignment's creators. A grader may grade, but doesn't pick who else grades.
 */
class AssignmentSubmissionGraderChoiceTest {

    private final AssignmentSubmissionJPA submissionRepo = mock(AssignmentSubmissionJPA.class);
    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private AssignmentSubmissionAPIController controller;

    private AssignmentSubmission work;
    private Person peer;

    @BeforeEach
    void setUp() {
        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        Person creator = signedIn(student(1L, "cora"));
        Person assignmentGrader = signedIn(student(2L, "gus"));
        Person submissionGrader = signedIn(student(3L, "pia"));
        peer = signedIn(student(4L, "niko"));
        signedIn(teacher(5L, "tess"));
        signedIn(admin(6L, "root"));

        Assignment frq = assignment(10L, "frq-1", creator);
        frq.setAssignedGraders(new ArrayList<>(List.of(assignmentGrader)));
        work = submission(100L, frq);
        work.setAssignedGraders(new ArrayList<>(List.of(submissionGrader)));
        when(submissionRepo.findById(100L)).thenReturn(Optional.of(work));
        when(submissionRepo.findById(999L)).thenReturn(Optional.empty());
        when(personRepo.findAllById(anyList())).thenReturn(List.of(peer));
    }

    @Test
    void teachersAdminsAndTheAssignmentsCreatorChooseGraders() {
        for (String uid : List.of("tess", "root", "cora")) {
            assertEquals(HttpStatus.OK, choose(uid).getStatusCode(), uid);
        }
        assertEquals(List.of(peer), work.getAssignedGraders());
    }

    @Test
    void anotherStudentCannot() {
        assertEquals(HttpStatus.FORBIDDEN, choose("niko").getStatusCode());
        assertEquals(List.of("pia"), work.getAssignedGraders().stream().map(Person::getUid).toList());
        verify(submissionRepo, never()).save(any(AssignmentSubmission.class));
    }

    /** Grading a submission doesn't make you the one who decides who else grades it. */
    @Test
    void gradersCannotChooseGradersEither() {
        assertEquals(HttpStatus.FORBIDDEN, choose("gus").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, choose("pia").getStatusCode());
        verify(submissionRepo, never()).save(any(AssignmentSubmission.class));
    }

    @Test
    void noSignedInAccountCannot() {
        assertEquals(HttpStatus.FORBIDDEN,
                controller.assignGradersToSubmission(100L, List.of(4L), null).getStatusCode());
        verify(submissionRepo, never()).save(any(AssignmentSubmission.class));
    }

    @Test
    void anUnknownSubmissionIsNotFound() {
        assertEquals(HttpStatus.NOT_FOUND,
                controller.assignGradersToSubmission(999L, List.of(4L), userDetails("tess")).getStatusCode());
    }

    private org.springframework.http.ResponseEntity<?> choose(String uid) {
        return controller.assignGradersToSubmission(100L, List.of(4L), userDetails(uid));
    }

    private Person signedIn(Person person) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        return person;
    }

    private static UserDetails userDetails(String uid) {
        return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
    }
}
