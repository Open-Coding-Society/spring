package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * A mentor reads, posts and shares files only in a group they were added to, with at least two members who aren't
 * mentors, and never in a class group. Everyone else's group chat works as before.
 */
class GroupChatMentorTest {

    private GroupChatService chat;
    private GroupChatRealtimeService realtime;
    private GroupsJpaRepository groups;
    private ClassGroupMembershipService classGroups;
    private PersonJpaRepository people;
    private GroupChatApiController controller;

    private final Person student = MentorRulesTest.student(1);
    private final Person teammate = MentorRulesTest.student(2);
    private final Person mentor = MentorRulesTest.mentor(3);
    private final Person otherMentor = MentorRulesTest.mentor(4);
    private final Person outsider = MentorRulesTest.student(5);

    private static final long TEAM = 7L;
    private static final long SOLO = 8L;
    private static final long CLASS = 9L;

    private void group(long id, String name, boolean classGroup, Person... members) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenReturn(List.of(members));
        when(classGroups.isClassGroup(name)).thenReturn(classGroup);
    }

    @BeforeEach
    void setUp() {
        chat = mock(GroupChatService.class);
        realtime = mock(GroupChatRealtimeService.class);
        groups = mock(GroupsJpaRepository.class);
        classGroups = mock(ClassGroupMembershipService.class);
        people = mock(PersonJpaRepository.class);
        controller = new GroupChatApiController(chat, realtime, mock(GroupChatReadService.class), groups, people,
                new MentorAccess(groups, classGroups));
        for (Person person : List.of(student, teammate, mentor, otherMentor, outsider)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
        }
        group(TEAM, "Team Rocket", false, student, teammate, mentor);
        group(SOLO, "Just Us", false, student, mentor);
        group(CLASS, "CSA", true, student, teammate, mentor);
        when(chat.getMessages(anyString())).thenReturn(List.of());
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

    private static GroupChatMessage text(String body) {
        GroupChatMessage message = new GroupChatMessage();
        message.setMessage(body);
        return message;
    }

    @Test
    void aMentorWhoIsNotAMemberIsRefusedEverywhere() {
        signIn(otherMentor);
        String reason = "mentor_not_in_group";
        assertRefused(reason, controller.getMessages(TEAM));
        assertRefused(reason, controller.postMessage(TEAM, text("hello")));
        assertRefused(reason, controller.editMessage(TEAM, "m1", text("changed")));
        assertRefused(reason, controller.deleteMessage(TEAM, "m1"));
        assertRefused(reason, controller.markRead(TEAM));
        assertRefused(reason, controller.getSharedFiles(TEAM));
        assertRefused(reason, controller.uploadSharedFile(TEAM,
                new GroupChatApiController.FileUploadRequest("notes.txt", "aGk=")));
        verify(realtime, never()).publishMessage(anyLong(), anyString(), any(), any());
        verify(realtime, never()).publishFile(anyLong(), any(), anyString(), anyString());
        verify(chat, never()).getMessages(anyString());
    }

    @Test
    void aMemberMentorWithATeamOfTwoReadsAndPosts() {
        signIn(mentor);
        assertEquals(HttpStatus.OK, controller.getMessages(TEAM).getStatusCode());
        assertEquals(HttpStatus.OK, controller.postMessage(TEAM, text("hello team")).getStatusCode());
        verify(realtime).publishMessage(any(), any(), any(), any());
    }

    @Test
    void aMentorWithATeamOfOneIsRefused() {
        signIn(mentor);
        assertRefused("mentor_team_too_small", controller.getMessages(SOLO));
        assertRefused("mentor_team_too_small", controller.postMessage(SOLO, text("just us")));
    }

    @Test
    void aMentorInAClassGroupIsRefused() {
        signIn(mentor);
        assertRefused("mentor_class_group", controller.getMessages(CLASS));
    }

    @Test
    void aStudentWhoIsNotAMemberStillReadsAsBefore() {
        signIn(outsider);
        assertEquals(HttpStatus.OK, controller.getMessages(TEAM).getStatusCode());
        verify(groups, never()).findMembersOf(anyLong());
    }

    @Test
    void aMentorSeesOnlyTheirOwnChatAnalytics() {
        when(groups.findGroupsByPersonId(anyLong())).thenReturn(List.of());
        when(people.findById(1L)).thenReturn(Optional.of(student));
        when(people.findById(3L)).thenReturn(Optional.of(mentor));
        when(chat.getUserAnalytics(anyString(), any())).thenReturn(new java.util.HashMap<>());
        signIn(mentor);
        assertRefused("mentor_cannot_manage", controller.getUserAnalytics(1L));
        assertEquals(HttpStatus.OK, controller.getUserAnalytics(3L).getStatusCode());
    }
}
