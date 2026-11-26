package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
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
import com.open.spring.mvc.reactions.MessageReactionService;

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

    private final Map<Long, Groups> made = new HashMap<>();

    private void group(long id, String name, boolean classGroup, Person... members) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenReturn(List.of(members));
        when(classGroups.isClassGroup(name)).thenReturn(classGroup);
        made.put(id, group);
    }

    /** The groups person is in, as the repository lists them. */
    private void groupsOf(Person person, long... ids) {
        when(groups.findGroupsByPersonId(person.getId())).thenReturn(Arrays.stream(ids).mapToObj(made::get).toList());
    }

    @BeforeEach
    void setUp() {
        chat = mock(GroupChatService.class);
        realtime = mock(GroupChatRealtimeService.class);
        groups = mock(GroupsJpaRepository.class);
        classGroups = mock(ClassGroupMembershipService.class);
        people = mock(PersonJpaRepository.class);
        GroupChatReadService reads =
                new GroupChatReadService(mock(GroupChatReadStateJpaRepository.class), groups, chat);
        controller = new GroupChatApiController(chat, realtime, reads, groups, people,
                mock(MessageReactionService.class), new MentorAccess(groups, classGroups));
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

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> unreadIn(ResponseEntity<?> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return (Map<Long, Long>) ((Map<?, ?>) response.getBody()).get("unreadByGroup");
    }

    private GroupChatMessage fromTeammate() {
        return new GroupChatMessage(teammate.getName(), teammate.getUid(), "hi", "2026-11-25T00:00:01Z", null);
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
        assertRefused(reason, controller.getSharedFileIndex(TEAM));
        assertRefused(reason, controller.getSharedFileContent(TEAM, "notes.txt"));
        verify(realtime, never()).publishMessage(anyLong(), anyString(), any(), any());
        verify(realtime, never()).publishFile(anyLong(), any(), anyString(), anyString());
        verify(chat, never()).getMessages(anyString());
        verify(chat, never()).sharedFileIndex(anyString());
        verify(chat, never()).readSharedFile(anyString(), anyString());
    }

    /** Reactions are part of a group's chat, so they follow the same mentor rules. */
    @Test
    void aMentorWhoIsNotAMemberCannotSeeOrChangeReactions() {
        signIn(otherMentor);
        String reason = "mentor_not_in_group";
        String message = "0f8fad5b-d9cb-469f-a165-70867728950e";
        assertRefused(reason, controller.getReactions(TEAM));
        assertRefused(reason, controller.addReaction(TEAM, message, "like"));
        assertRefused(reason, controller.removeReaction(TEAM, message, "like"));
        verify(realtime, never()).setReaction(anyLong(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    void aMemberMentorWithATeamOfTwoReacts() {
        signIn(mentor);
        assertEquals(HttpStatus.OK, controller.getReactions(TEAM).getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.addReaction(TEAM, "0f8fad5b-d9cb-469f-a165-70867728950e", "like").getStatusCode());
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
        assertRefused("mentor_team_too_small", controller.getSharedFileIndex(SOLO));
        assertRefused("mentor_team_too_small", controller.getSharedFileContent(SOLO, "notes.txt"));
    }

    @Test
    void aMemberMentorWithATeamOfTwoListsAndDownloadsTheTeamsFiles() {
        when(chat.readSharedFile("Team Rocket", "notes.txt")).thenReturn("hi".getBytes(StandardCharsets.UTF_8));
        signIn(mentor);
        assertEquals(HttpStatus.OK, controller.getSharedFileIndex(TEAM).getStatusCode());
        ResponseEntity<?> file = controller.getSharedFileContent(TEAM, "notes.txt");
        assertEquals(HttpStatus.OK, file.getStatusCode());
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), (byte[]) file.getBody());
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
        assertEquals(HttpStatus.OK, controller.getSharedFileIndex(TEAM).getStatusCode());
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

    @Test
    void aMentorsUnreadCountLeavesOutTheGroupsTheyMayNotUse() {
        groupsOf(mentor, TEAM, SOLO, CLASS);
        when(chat.getMessages(anyString())).thenReturn(List.of(fromTeammate()));
        signIn(mentor);
        assertEquals(Map.of(TEAM, 1L), unreadIn(controller.getUnread()));
    }

    @Test
    void everyoneElsesUnreadCountCoversAllTheirGroups() {
        groupsOf(student, TEAM, SOLO, CLASS);
        when(chat.getMessages(anyString())).thenReturn(List.of(fromTeammate()));
        signIn(student);
        assertEquals(Map.of(TEAM, 1L, SOLO, 1L, CLASS, 1L), unreadIn(controller.getUnread()));
    }
}
