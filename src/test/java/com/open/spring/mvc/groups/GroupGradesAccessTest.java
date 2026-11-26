package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAuthorizationService;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Who may change a group's grades. Replacing or clearing all of them is for teachers and admins. Adding one
 * assignment's entry, which also copies it into each member's own grades, is for them and for whoever already
 * grades the group on that assignment: its creators and graders, and the graders of the group's submission to it.
 */
class GroupGradesAccessTest {

    private static final long TEAM = 7L;
    private static final long HW1 = 101L;
    private static final long HW2 = 102L;

    private final GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
    private final PersonJpaRepository people = mock(PersonJpaRepository.class);
    private final ClassGroupMembershipService classGroups = mock(ClassGroupMembershipService.class);
    private final AssignmentJpaRepository assignments = mock(AssignmentJpaRepository.class);
    private final AssignmentSubmissionJPA submissions = mock(AssignmentSubmissionJPA.class);
    private GroupsApiController controller;

    private final Person member = MentorRulesTest.student(1);
    private final Person teammate = MentorRulesTest.student(2);
    private final Person outsider = MentorRulesTest.student(5);
    private final Person assignmentGrader = MentorRulesTest.student(6);
    private final Person submissionGrader = MentorRulesTest.student(7);
    private final Person creator = MentorRulesTest.student(9);
    private final Person teacher = MentorRulesTest.teacher(8);
    private final Person admin = MentorRulesTest.person(10, "ROLE_USER", "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        controller = new GroupsApiController();
        ReflectionTestUtils.setField(controller, "groupsRepository", groups);
        ReflectionTestUtils.setField(controller, "personRepository", people);
        ReflectionTestUtils.setField(controller, "groupChatService", mock(GroupChatService.class));
        ReflectionTestUtils.setField(controller, "classGroupMembershipService", classGroups);
        ReflectionTestUtils.setField(controller, "mentorAccess", new MentorAccess(groups, classGroups));
        ReflectionTestUtils.setField(controller, "assignmentRepository", assignments);
        ReflectionTestUtils.setField(controller, "submissionRepository", submissions);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        for (Person person : List.of(member, teammate, outsider, assignmentGrader, submissionGrader, creator, teacher, admin)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
        }

        Groups team = new Groups();
        ReflectionTestUtils.setField(team, "id", TEAM);
        team.setName("Team Rocket");
        team.getGroupMembers().addAll(List.of(member, teammate));
        when(groups.findById(TEAM)).thenReturn(Optional.of(team));
        when(groups.save(any(Groups.class))).thenAnswer(call -> call.getArgument(0));
        when(groups.findGroupMembersRaw(TEAM)).thenReturn(List.of(
                new Object[] {1L, member.getUid(), member.getName(), null},
                new Object[] {2L, teammate.getUid(), teammate.getName(), null}));
        when(people.findAllById(anyList())).thenReturn(List.of(member, teammate));

        Assignment hw1 = assignment(HW1, "hw-1");
        hw1.setAssignedGraders(new ArrayList<>(List.of(assignmentGrader)));
        hw1.getCreators().add(creator);
        when(assignments.findById(HW1)).thenReturn(Optional.of(hw1));
        when(assignments.findById(HW2)).thenReturn(Optional.of(assignment(HW2, "hw-2")));

        AssignmentSubmission teamHw1 = new AssignmentSubmission();
        teamHw1.setId(55L);
        teamHw1.setAssignment(hw1);
        teamHw1.setSubmitter(team);
        teamHw1.setAssignedGraders(new ArrayList<>(List.of(submissionGrader)));
        when(submissions.findBySubmitterId(TEAM)).thenReturn(List.of(teamHw1));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** Being in the group, or outside it, isn't grading it. */
    @Test
    void membersAndOutsidersCannotChangeAGroupsGrades() {
        for (Person notGrader : List.of(member, outsider)) {
            signIn(notGrader);
            assertEquals(HttpStatus.FORBIDDEN, controller.putGroupGrades(TEAM, grades()).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.clearGroupGrades(TEAM).getStatusCode());
        }
        verify(groups, never()).save(any(Groups.class));
        verify(people, never()).saveAll(anyList());
    }

    @Test
    void teachersAndAdminsReplaceAddAndClearAGroupsGrades() {
        for (Person staff : List.of(teacher, admin)) {
            signIn(staff);
            assertEquals(HttpStatus.OK, controller.putGroupGrades(TEAM, grades()).getStatusCode());
            assertEquals(HttpStatus.OK, controller.addGroupGrade(TEAM, entry(HW2)).getStatusCode());
            assertEquals(HttpStatus.NO_CONTENT, controller.clearGroupGrades(TEAM).getStatusCode());
        }
    }

    /** hw-1's creator, its grader and the grader of the group's hw-1 submission add that entry and nothing else. */
    @Test
    void whoeverGradesTheGroupOnAnAssignmentAddsOnlyThatEntry() {
        for (Person grader : List.of(assignmentGrader, creator, submissionGrader)) {
            signIn(grader);
            assertEquals(HttpStatus.OK, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.addGroupGrade(TEAM, entry(HW2)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.putGroupGrades(TEAM, grades()).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.clearGroupGrades(TEAM).getStatusCode());
        }
    }

    /** hw-1's creator and grader grade it for every group, even one that hasn't handed anything in yet. */
    @Test
    void anAssignmentsGradersAddItsEntryBeforeTheGroupHandsIn() {
        when(submissions.findBySubmitterId(TEAM)).thenReturn(List.of());
        for (Person grader : List.of(assignmentGrader, creator)) {
            signIn(grader);
            assertEquals(HttpStatus.OK, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
        }
        signIn(submissionGrader);
        assertEquals(HttpStatus.FORBIDDEN, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
    }

    @Test
    void anAddedEntryIsCopiedIntoEachMembersGrades() {
        signIn(assignmentGrader);
        assertEquals(HttpStatus.OK, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
        for (Person person : List.of(member, teammate)) {
            assertTrue(person.getGradesJson().stream()
                    .anyMatch(grade -> String.valueOf(HW1).equals(String.valueOf(grade.get("assignmentId")))));
        }
    }

    /** Without an assignment there's no one who grades it, so only teachers and admins add such an entry. */
    @Test
    void anEntryWithoutAnAssignmentIsForTeachersAndAdmins() {
        signIn(assignmentGrader);
        assertEquals(HttpStatus.FORBIDDEN, controller.addGroupGrade(TEAM, new HashMap<>(Map.of("score", 90))).getStatusCode());

        signIn(teacher);
        assertEquals(HttpStatus.OK, controller.addGroupGrade(TEAM, new HashMap<>(Map.of("score", 90))).getStatusCode());
    }

    @Test
    void signedOutCannotChangeAGroupsGrades() {
        assertEquals(HttpStatus.FORBIDDEN, controller.putGroupGrades(TEAM, grades()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.addGroupGrade(TEAM, entry(HW1)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.clearGroupGrades(TEAM).getStatusCode());
    }

    private static Assignment assignment(long id, String name) {
        Assignment assignment = new Assignment(name, "auto-created", "[CONTENT_URL: csa/" + name + "/]", 1.0, "10/25/2026", "file");
        assignment.setId(id);
        return assignment;
    }

    private static List<Map<String, Object>> grades() {
        return new ArrayList<>(List.of(new HashMap<>(Map.of("assignmentId", HW1, "score", 95))));
    }

    private static Map<String, Object> entry(long assignmentId) {
        return new HashMap<>(Map.of("assignmentId", assignmentId, "score", 90));
    }

    private void signIn(Person person) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(person.getUid(), null, List.of()));
    }
}
