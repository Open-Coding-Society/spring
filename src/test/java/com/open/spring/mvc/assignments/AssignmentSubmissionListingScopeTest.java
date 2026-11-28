package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * The two submission listings (by assignment and by student) show the caller only the
 * submissions they may see: their own, and the ones they may manage (teachers, admins, the
 * assignment's creators and graders, and graders of that submission).
 */
class AssignmentSubmissionListingScopeTest {

    private final AssignmentSubmissionJPA submissionRepo = mock(AssignmentSubmissionJPA.class);
    private final AssignmentJpaRepository assignmentRepo = mock(AssignmentJpaRepository.class);
    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private final GroupsJpaRepository groupRepo = mock(GroupsJpaRepository.class);
    private AssignmentSubmissionAPIController controller;

    private Person hop;

    @BeforeEach
    void setUp() {
        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "groupRepo", groupRepo);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        Person creator = signedIn(student(1L, "cora"));
        Person grader = signedIn(student(2L, "gus"));
        Person peerGrader = signedIn(student(3L, "pia"));
        hop = signedIn(student(4L, "hop"));
        Person niko = signedIn(student(5L, "niko"));
        signedIn(teacher(6L, "tess"));
        signedIn(admin(7L, "root"));

        Assignment frq = assignment(10L, "frq-1", creator);
        frq.setAssignedGraders(new ArrayList<>(List.of(grader)));
        Assignment other = assignment(11L, "other");
        AssignmentSubmission hopsFrq = handedIn(100L, frq, hop);
        hopsFrq.setAssignedGraders(new ArrayList<>(List.of(peerGrader)));
        AssignmentSubmission nikosFrq = handedIn(101L, frq, niko);
        AssignmentSubmission hopsOther = handedIn(102L, other, hop);

        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(frq));
        when(submissionRepo.findByAssignmentId(10L)).thenReturn(List.of(hopsFrq, nikosFrq));
        when(personRepo.findById(4L)).thenReturn(Optional.of(hop));
        when(submissionRepo.findBySubmitterId(4L)).thenReturn(List.of(hopsFrq, hopsOther));
    }

    @Test
    void teachersAdminsCreatorsAndGradersSeeAnAssignmentsSubmissions() {
        for (String uid : List.of("tess", "root", "cora", "gus")) {
            assertEquals(List.of(100L, 101L), ids(controller.extractSubmissionsByAssignment(10L, userDetails(uid))), uid);
        }
    }

    @Test
    void aStudentSeesOnlyTheirOwnAndOnesTheyGrade() {
        assertEquals(List.of(100L), ids(controller.extractSubmissionsByAssignment(10L, userDetails("hop"))));
        assertEquals(List.of(101L), ids(controller.extractSubmissionsByAssignment(10L, userDetails("niko"))));
        assertEquals(List.of(100L), ids(controller.extractSubmissionsByAssignment(10L, userDetails("pia"))));
        assertEquals(List.of(), ids(controller.extractSubmissionsByAssignment(10L, null)));
    }

    @Test
    void aStudentsListShowsWhatTheCallerMaySee() {
        assertEquals(List.of(100L, 102L), ids(controller.getSubmissions(4L, userDetails("hop"))));
        assertEquals(List.of(100L, 102L), ids(controller.getSubmissions(4L, userDetails("tess"))));
        assertEquals(List.of(100L), ids(controller.getSubmissions(4L, userDetails("cora"))));
        assertEquals(List.of(), ids(controller.getSubmissions(4L, userDetails("niko"))));
        assertEquals(List.of(), ids(controller.getSubmissions(4L, null)));
    }

    private static List<Long> ids(ResponseEntity<?> response) {
        return ((List<?>) response.getBody()).stream()
                .map(dto -> ((AssignmentSubmissionReturnDto) dto).getId())
                .toList();
    }

    private static AssignmentSubmission handedIn(Long id, Assignment assignment, Person submitter) {
        AssignmentSubmission work = submission(id, assignment);
        work.setSubmitter(submitter);
        work.setContent(Map.of("type", "link", "url", "https://example.com/" + id));
        return work;
    }

    private Person signedIn(Person person) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        return person;
    }

    private static UserDetails userDetails(String uid) {
        return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
    }
}
