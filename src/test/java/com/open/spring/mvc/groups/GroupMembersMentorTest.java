package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * The team manages its mentors: a member of a group (or a teacher) adds and removes its mentor, a mentor adds no one,
 * and a mentor never ends up alone with one student. Mentors can't look through or change groups either. Students
 * manage everyone else as before.
 */
class GroupMembersMentorTest {

    private GroupsJpaRepository groups;
    private PersonJpaRepository people;
    private ClassGroupMembershipService classGroups;
    private GroupsApiController controller;

    private final Person student = MentorRulesTest.student(1);
    private final Person teammate = MentorRulesTest.student(2);
    private final Person mentor = MentorRulesTest.mentor(3);
    private final Person outsider = MentorRulesTest.student(5);
    private final Person teacher = MentorRulesTest.teacher(8);
    private final Person thirdStudent = MentorRulesTest.student(6);

    private static final long TEAM = 7L;
    private static final long SOLO = 8L;

    /** The group's members as the database holds them: a group answers from these rows. */
    private final Map<Long, List<Person>> members = new java.util.HashMap<>();

    private Groups group(long id, String name, Person... initial) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        members.put(id, new ArrayList<>(List.of(initial)));
        for (Person person : initial) {
            group.getGroupMembers().add(person);
        }
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenAnswer(call -> List.copyOf(members.get(id)));
        when(groups.findGroupMembersWithRolesRaw(id)).thenAnswer(call -> rows(members.get(id)));
        when(groups.save(group)).thenAnswer(call -> {
            members.put(id, new ArrayList<>(group.getGroupMembers()));
            return group;
        });
        return group;
    }

    private static List<Object[]> rows(List<Person> people) {
        List<Object[]> rows = new ArrayList<>();
        for (Person person : people) {
            rows.add(new Object[] {person.getId(), person.getUid(), person.getName(), person.getUid() + "@example.com",
                    new com.open.spring.mvc.person.RolesJsonConverter().convertToDatabaseColumn(person.getRoles())});
        }
        return rows;
    }

    @BeforeEach
    void setUp() {
        groups = mock(GroupsJpaRepository.class);
        people = mock(PersonJpaRepository.class);
        classGroups = mock(ClassGroupMembershipService.class);
        controller = new GroupsApiController();
        ReflectionTestUtils.setField(controller, "groupsRepository", groups);
        ReflectionTestUtils.setField(controller, "personRepository", people);
        ReflectionTestUtils.setField(controller, "groupChatService", mock(GroupChatService.class));
        ReflectionTestUtils.setField(controller, "classGroupMembershipService", classGroups);
        ReflectionTestUtils.setField(controller, "mentorAccess", new MentorAccess(groups, classGroups));
        for (Person person : List.of(student, teammate, mentor, outsider, teacher, thirdStudent)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
            when(people.findById(person.getId())).thenReturn(Optional.of(person));
        }
        group(TEAM, "Team Rocket", student, teammate);
        group(SOLO, "Just Us", student);
        when(groups.findAll()).thenReturn(List.of());
        when(groups.searchByName(anyString())).thenReturn(List.of());
        when(groups.findGroupsByPersonId(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(Person person) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(person.getUid(), null, List.of()));
    }

    private static void assertRefused(String reason, ResponseEntity<?> response) {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(reason, ((Map<?, ?>) response.getBody()).get("reason"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> memberById(ResponseEntity<?> response, long id) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("members");
        return list.stream().filter(member -> ((Number) member.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    @Test
    void aTeamMemberAddsAMentorAndTheMemberListMarksThem() {
        signIn(student);
        ResponseEntity<?> response = controller.addPersonToGroup(TEAM, mentor.getId());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, memberById(response, mentor.getId()).get("mentor"));
        assertEquals(false, memberById(response, student.getId()).get("mentor"));
    }

    @Test
    void aStudentOutsideTheTeamCannotAddAMentor() {
        signIn(outsider);
        assertRefused("team_manages_mentors", controller.addPersonToGroup(TEAM, mentor.getId()));
        verify(groups, never()).save(any());
    }

    @Test
    void aMentorCannotAddThemselvesOrAnyoneElse() {
        signIn(mentor);
        assertRefused("mentor_cannot_manage", controller.addPersonToGroup(TEAM, mentor.getId()));
        assertRefused("mentor_cannot_manage", controller.addPersonToGroup(TEAM, outsider.getId()));
        verify(groups, never()).save(any());
    }

    @Test
    void aMentorCannotBeAddedToAOneStudentGroup() {
        signIn(student);
        assertRefused("mentor_team_too_small", controller.addPersonToGroup(SOLO, mentor.getId()));
    }

    @Test
    void aTeacherAddsAMentor() {
        signIn(teacher);
        assertEquals(HttpStatus.OK, controller.addPersonToGroup(TEAM, mentor.getId()).getStatusCode());
    }

    @Test
    void studentsStillAddStudentsAsBefore() {
        signIn(outsider);
        assertEquals(HttpStatus.OK, controller.addPersonToGroup(TEAM, thirdStudent.getId()).getStatusCode());
    }

    @Test
    void aMentorLeavesButCannotRemoveOthers() {
        signIn(student);
        controller.addPersonToGroup(TEAM, mentor.getId());
        signIn(mentor);
        assertRefused("mentor_cannot_manage", controller.removePersonFromGroup(TEAM, teammate.getId()));
        assertEquals(HttpStatus.OK, controller.removePersonFromGroup(TEAM, mentor.getId()).getStatusCode());
        assertFalse(members.get(TEAM).contains(mentor));
    }

    @Test
    void onlyTheTeamOrATeacherRemovesTheMentor() {
        signIn(student);
        controller.addPersonToGroup(TEAM, mentor.getId());
        signIn(outsider);
        assertRefused("team_manages_mentors", controller.removePersonFromGroup(TEAM, mentor.getId()));
        signIn(teammate);
        assertEquals(HttpStatus.OK, controller.removePersonFromGroup(TEAM, mentor.getId()).getStatusCode());
    }

    @Test
    void aNewGroupStartsWithAMentorOnlyWhenTheTeamCouldAddThem() {
        when(groups.findByName(anyString())).thenReturn(Optional.empty());
        when(groups.save(any(Groups.class))).thenAnswer(call -> call.getArgument(0));
        signIn(student);
        assertRefused("mentor_team_too_small", controller.createGroup(new GroupsApiController.GroupCreateDto(
                "Pair", "3", "CSP", List.of(student.getId(), mentor.getId()))));
        assertRefused("team_manages_mentors", controller.createGroup(new GroupsApiController.GroupCreateDto(
                "Not mine", "3", "CSP", List.of(teammate.getId(), thirdStudent.getId(), mentor.getId()))));
        assertEquals(HttpStatus.CREATED, controller.createGroup(new GroupsApiController.GroupCreateDto(
                "Our team", "3", "CSP", List.of(student.getId(), teammate.getId(), mentor.getId()))).getStatusCode());
    }

    @Test
    void aStudentListedTwiceIsStillOneStudent() {
        when(groups.findByName(anyString())).thenReturn(Optional.empty());
        when(groups.save(any(Groups.class))).thenAnswer(call -> call.getArgument(0));
        signIn(student);
        assertRefused("mentor_team_too_small", controller.createGroup(new GroupsApiController.GroupCreateDto(
                "Pair again", "3", "CSP", List.of(student.getId(), student.getId(), mentor.getId()))));
    }

    @Test
    void bulkCreateSkipsAGroupThatWouldBreakTheMentorRule() {
        when(groups.findByName(anyString())).thenReturn(Optional.empty());
        when(groups.save(any(Groups.class))).thenAnswer(call -> call.getArgument(0));
        signIn(teacher);
        GroupsApiController.BulkGroupCreateDto dto = new GroupsApiController.BulkGroupCreateDto(List.of(
                new GroupsApiController.GroupCreateDto("Pair", "3", "CSP", List.of(student.getId(), mentor.getId())),
                new GroupsApiController.GroupCreateDto("Team", "3", "CSP",
                        List.of(student.getId(), teammate.getId(), mentor.getId()))));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.bulkCreateGroups(dto).getBody();
        assertEquals(1, ((List<?>) body.get("created")).size());
        assertTrue(body.get("errors").toString().contains("Pair"));
    }

    @Test
    void mentorsCannotLookThroughOrChangeGroups() {
        signIn(mentor);
        String reason = "mentor_cannot_manage";
        assertRefused(reason, controller.getAllGroups());
        assertRefused(reason, controller.searchGroupsByName("Team"));
        assertRefused(reason, controller.createGroup(new GroupsApiController.GroupCreateDto("Mine", "3", "CSP", List.of())));
        assertRefused(reason, controller.bulkCreateGroups(new GroupsApiController.BulkGroupCreateDto(List.of())));
        assertRefused(reason, controller.updateGroup(TEAM, new GroupsApiController.GroupUpdateDto("Renamed", null, null)));
        assertRefused(reason, controller.deleteGroup(TEAM));
        assertRefused(reason, controller.getGroupGrades(TEAM));
        assertRefused(reason, controller.putGroupGrades(TEAM, new ArrayList<>()));
        assertRefused(reason, controller.addGroupGrade(TEAM, new java.util.HashMap<>()));
        assertRefused(reason, controller.clearGroupGrades(TEAM));
        assertRefused(reason, controller.getGroupsByPersonId(student.getId()));
        assertRefused("mentor_class_group", controller.syncClassMemberships(
                org.springframework.security.core.userdetails.User.withUsername(mentor.getUid())
                        .password("unused").authorities("ROLE_USER").build(),
                new GroupsApiController.ClassMembershipSyncDto(List.of("CSA"))));
        verify(groups, never()).deleteById(anyLong());
    }

    @Test
    void aMentorSeesTheirOwnGroupsAndTheGroupsTheyAreIn() {
        signIn(student);
        controller.addPersonToGroup(TEAM, mentor.getId());
        signIn(mentor);
        assertEquals(HttpStatus.OK, controller.getGroupsByPersonId(mentor.getId()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getGroupById(TEAM).getStatusCode());
        assertRefused("mentor_not_in_group", controller.getGroupById(SOLO));
    }

    @Test
    void studentsListAndSearchGroupsAsBefore() {
        signIn(outsider);
        assertEquals(HttpStatus.OK, controller.getAllGroups().getStatusCode());
        assertEquals(HttpStatus.OK, controller.searchGroupsByName("Team").getStatusCode());
        assertEquals(HttpStatus.OK, controller.getGroupById(SOLO).getStatusCode());
    }

    @Test
    void aMentorsOwnListHasOnlyTheGroupsTheyMayUse() {
        Groups team = group(20L, "Team Plasma", student, teammate, mentor);
        Groups solo = group(21L, "Pat And Ana", student, mentor);
        Groups csa = group(22L, "CSA", student, teammate, thirdStudent, mentor);
        when(classGroups.isClassGroup("CSA")).thenReturn(true);
        when(groups.findGroupsByPersonId(mentor.getId())).thenReturn(List.of(team, solo, csa));
        when(groups.findGroupsByPersonId(student.getId())).thenReturn(List.of(team, solo, csa));

        signIn(mentor);
        assertEquals(List.of(20L), groupIds(controller.getGroupsByPersonId(mentor.getId())));
        signIn(student);
        assertEquals(List.of(20L, 21L, 22L), groupIds(controller.getGroupsByPersonId(student.getId())));
    }

    @Test
    void aMentorMayLeaveEveryClassGroupButJoinNone() {
        org.springframework.security.core.userdetails.UserDetails login = org.springframework.security.core.userdetails.User
                .withUsername(mentor.getUid()).password("unused").authorities("ROLE_USER").build();
        when(classGroups.syncMemberships(mentor.getUid(), List.of())).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.syncClassMemberships(login,
                new GroupsApiController.ClassMembershipSyncDto(List.of())).getStatusCode());
        assertRefused("mentor_class_group", controller.syncClassMemberships(login,
                new GroupsApiController.ClassMembershipSyncDto(List.of("CSA"))));
        verify(classGroups, never()).syncMemberships(mentor.getUid(), List.of("CSA"));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> groupIds(ResponseEntity<?> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return ((List<Map<String, Object>>) response.getBody()).stream()
                .map(group -> ((Number) group.get("id")).longValue()).toList();
    }
}
