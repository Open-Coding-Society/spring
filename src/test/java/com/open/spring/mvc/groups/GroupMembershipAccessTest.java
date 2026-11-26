package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Who may change a group. A group has no owner, so its members add and remove people, as the
 * Messages page's Members dialog already lets them, and so do teachers and admins. Anyone may join
 * or leave a group that isn't a class group (Join and Leave), but a class group's members come from
 * the classes on each student's Profile. Changing a group's name, period or course is for teachers
 * and admins, and a group name has no "/", because group chat stores its files under the name.
 */
class GroupMembershipAccessTest {

    private static final long TEAM = 7L;
    private static final long CLASS = 9L;

    private final GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
    private final PersonJpaRepository people = mock(PersonJpaRepository.class);
    private final ClassGroupMembershipService classGroups = mock(ClassGroupMembershipService.class);
    private GroupsApiController controller;

    private final Person member = MentorRulesTest.student(1);
    private final Person teammate = MentorRulesTest.student(2);
    private final Person outsider = MentorRulesTest.student(5);
    private final Person another = MentorRulesTest.student(6);
    private final Person teacher = MentorRulesTest.teacher(8);
    private final Person admin = MentorRulesTest.person(10, "ROLE_USER", "ROLE_ADMIN");
    private Groups team;

    @BeforeEach
    void setUp() {
        controller = new GroupsApiController();
        ReflectionTestUtils.setField(controller, "groupsRepository", groups);
        ReflectionTestUtils.setField(controller, "personRepository", people);
        ReflectionTestUtils.setField(controller, "groupChatService", mock(GroupChatService.class));
        ReflectionTestUtils.setField(controller, "classGroupMembershipService", classGroups);
        ReflectionTestUtils.setField(controller, "mentorAccess", new MentorAccess(groups, classGroups));
        for (Person person : List.of(member, teammate, outsider, another, teacher, admin)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
            when(people.findById(person.getId())).thenReturn(Optional.of(person));
        }
        team = group(TEAM, "Team Rocket", member, teammate);
        group(CLASS, "CSA", member);
        when(classGroups.isClassGroup("CSA")).thenReturn(true);
        when(groups.findGroupMembersWithRolesRaw(anyLong())).thenReturn(List.of());
        when(groups.findGroupsByPersonId(anyLong())).thenReturn(List.of());
        when(groups.findByName(anyString())).thenReturn(Optional.empty());
        when(groups.save(any(Groups.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aMemberAddsAndRemovesOthers() {
        signIn(member);

        assertEquals(HttpStatus.OK, controller.addPersonToGroup(TEAM, another.getId()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.removePersonFromGroup(TEAM, teammate.getId()).getStatusCode());
    }

    @Test
    void someoneOutsideTheGroupCannotChangeWhoIsInIt() {
        signIn(outsider);

        assertEquals(HttpStatus.FORBIDDEN, controller.addPersonToGroup(TEAM, another.getId()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.removePersonFromGroup(TEAM, teammate.getId()).getStatusCode());
        assertEquals(List.of(member, teammate), team.getGroupMembers());
    }

    /** Messages' Find a group adds the signed-in person, and Leave removes them. */
    @Test
    void anyoneJoinsAndLeavesAGroupThatIsNotAClassGroup() {
        signIn(outsider);

        assertEquals(HttpStatus.OK, controller.addPersonToGroup(TEAM, outsider.getId()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.removePersonFromGroup(TEAM, outsider.getId()).getStatusCode());
    }

    /** The classes on each student's Profile decide who is in a class group, so on these routes only teachers and admins change one. */
    @Test
    void onlyTeachersAndAdminsChangeAClassGroup() {
        signIn(outsider);
        assertEquals(HttpStatus.FORBIDDEN, controller.addPersonToGroup(CLASS, outsider.getId()).getStatusCode());

        signIn(member);
        assertEquals(HttpStatus.FORBIDDEN, controller.addPersonToGroup(CLASS, another.getId()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.removePersonFromGroup(CLASS, member.getId()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.updateGroup(CLASS, new GroupsApiController.GroupUpdateDto("Not CSA", null, null)).getStatusCode());

        signIn(teacher);
        assertEquals(HttpStatus.OK, controller.addPersonToGroup(CLASS, outsider.getId()).getStatusCode());
    }

    @Test
    void teachersAndAdminsManageAnyGroup() {
        for (Person staff : List.of(teacher, admin)) {
            signIn(staff);
            assertEquals(HttpStatus.OK, controller.addPersonToGroup(TEAM, another.getId()).getStatusCode());
            assertEquals(HttpStatus.OK, controller.removePersonFromGroup(TEAM, another.getId()).getStatusCode());
            assertEquals(HttpStatus.OK, rename("Team Rocket 2").getStatusCode());
        }
    }

    /** Nothing in pages renames a group, and anyone can be a member of a group they just joined. */
    @Test
    void onlyTeachersAndAdminsRenameAGroup() {
        for (Person notStaff : List.of(outsider, member)) {
            signIn(notStaff);
            assertEquals(HttpStatus.FORBIDDEN, rename("Mine now").getStatusCode());
        }
        assertEquals("Team Rocket", team.getName());

        signIn(teacher);
        assertEquals(HttpStatus.OK, rename("Team Rocket 2").getStatusCode());
        assertEquals("Team Rocket 2", team.getName());
    }

    /** Profile's class sync, a course's video call room and the assignment course sync find a group by its name. */
    @Test
    void aRenameCannotTakeAnotherGroupsName() {
        signIn(teacher);
        when(groups.findByName("Other Team")).thenReturn(Optional.of(new Groups()));

        assertEquals(HttpStatus.CONFLICT, rename("Other Team").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, rename("CSA").getStatusCode());
        assertEquals("Team Rocket", team.getName());
    }

    /** Those lookups find a class group by its exact name too, so it keeps it. Its period and course can still change. */
    @Test
    void aClassGroupKeepsItsName() {
        Groups csa = groups.findById(CLASS).orElseThrow();
        for (Person staff : List.of(teacher, admin)) {
            signIn(staff);
            for (String newName : List.of("Not CSA", "csa")) {
                assertEquals(HttpStatus.FORBIDDEN, controller.updateGroup(CLASS,
                        new GroupsApiController.GroupUpdateDto(newName, null, null)).getStatusCode());
            }
        }
        assertEquals("CSA", csa.getName());

        signIn(teacher);
        assertEquals(HttpStatus.OK, controller.updateGroup(CLASS,
                new GroupsApiController.GroupUpdateDto("CSA", "3", null)).getStatusCode());
        assertEquals("3", csa.getPeriod());
    }

    /**
     * Deleting a group also deletes its grades and its group submissions. The Messages page offers
     * Delete only to admins, and never for a class group.
     */
    @Test
    void onlyAnAdminDeletesAGroupAndNeverAClassGroup() {
        for (Person notAdmin : List.of(outsider, member, teacher)) {
            signIn(notAdmin);
            assertEquals(HttpStatus.FORBIDDEN, controller.deleteGroup(TEAM).getStatusCode());
        }
        signIn(admin);
        assertEquals(HttpStatus.FORBIDDEN, controller.deleteGroup(CLASS).getStatusCode());
        verify(groups, never()).deleteById(anyLong());

        assertEquals(HttpStatus.OK, controller.deleteGroup(TEAM).getStatusCode());
        verify(groups).deleteById(TEAM);
    }

    @Test
    void signedOutCannotChangeAGroup() {
        assertEquals(HttpStatus.FORBIDDEN, controller.addPersonToGroup(TEAM, outsider.getId()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.removePersonFromGroup(TEAM, teammate.getId()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, rename("x").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.deleteGroup(TEAM).getStatusCode());
    }

    @Test
    void aGroupNameHasNoSlash() {
        signIn(teacher);
        assertEquals(HttpStatus.BAD_REQUEST, rename("hop/team").getStatusCode());
        assertEquals("Team Rocket", team.getName());

        signIn(member);
        assertEquals(HttpStatus.BAD_REQUEST, controller.createGroup(
                new GroupsApiController.GroupCreateDto("hop/team", "1", "csa", List.of(member.getId()))).getStatusCode());

        signIn(teacher);
        Object bulk = controller.bulkCreateGroups(new GroupsApiController.BulkGroupCreateDto(List.of(
                new GroupsApiController.GroupCreateDto("hop/team", "1", "csa", List.of())))).getBody();
        assertEquals(List.of(), ((Map<?, ?>) bulk).get("created"));
        verify(groups, never()).save(any(Groups.class));
    }

    private org.springframework.http.ResponseEntity<?> rename(String name) {
        return controller.updateGroup(TEAM, new GroupsApiController.GroupUpdateDto(name, null, null));
    }

    private Groups group(long id, String name, Person... initial) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        group.getGroupMembers().addAll(new ArrayList<>(List.of(initial)));
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenAnswer(call -> List.copyOf(group.getGroupMembers()));
        return group;
    }

    private void signIn(Person person) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(person.getUid(), null, List.of()));
    }
}
