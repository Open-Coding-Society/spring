package com.open.spring.mvc.groups;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Group events on the chat socket follow the mentor rules, as REST does: a socket with a signed-in mentor sends,
 * joins and types only in groups the mentor may use (MentorRules). Leaving always works, and everyone else's events
 * work as before. A socket from Spring's own pages carries its sign-in's roles, so only a mentor's events load anyone.
 */
class GroupChatSocketMentorTest {

    private GroupChatRealtimeService realtime;
    private GroupChatPresenceService presence;
    private GroupsJpaRepository groups;
    private ClassGroupMembershipService classGroups;
    private PersonJpaRepository people;
    private GroupChatWebSocketController controller;

    private final Person student = MentorRulesTest.student(1);
    private final Person teammate = MentorRulesTest.student(2);
    private final Person mentor = MentorRulesTest.mentor(3);

    private static final long OTHERS = 7L;
    private static final long SOLO = 8L;
    private static final long TEAM = 9L;

    private void group(long id, String name, Person... members) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenReturn(List.of(members));
    }

    @BeforeEach
    void setUp() {
        realtime = mock(GroupChatRealtimeService.class);
        presence = mock(GroupChatPresenceService.class);
        groups = mock(GroupsJpaRepository.class);
        classGroups = mock(ClassGroupMembershipService.class);
        people = mock(PersonJpaRepository.class);
        controller = new GroupChatWebSocketController(mock(GroupChatIdentityService.class), realtime, presence,
                new MentorAccess(groups, classGroups), people);
        for (Person person : List.of(student, teammate, mentor)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
        }
        group(OTHERS, "Team Rocket", student, teammate);
        group(SOLO, "Just Us", student, mentor);
        group(TEAM, "Team Plasma", student, teammate, mentor);
    }

    private void send(Person person, String context, long groupId) {
        GroupChatEvent event = GroupChatEvent.builder().context(context).groupId(groupId)
                .message("hi").filename("notes.txt").base64Data("aGk=").build();
        Principal principal = person::getUid;
        controller.handleGroupEvent(event, principal, "session-" + person.getId());
    }

    /** The same event from a socket whose sign-in carries the person's roles, as Spring's form login gives it. */
    private void sendSignedIn(Person person, String context, long groupId) {
        GroupChatEvent event = GroupChatEvent.builder().context(context).groupId(groupId).message("hi").build();
        List<SimpleGrantedAuthority> roles = person.getRoles().stream().map(SimpleGrantedAuthority::new).toList();
        controller.handleGroupEvent(event, new UsernamePasswordAuthenticationToken(person.getUid(), null, roles),
                "session-" + person.getId());
    }

    @Test
    void aMentorsSocketCantSendOrShowUpInAGroupTheyArentIn() {
        for (String context : List.of("joinGroup", "sendMessage", "sendFile", "typingStart", "heartbeat")) {
            send(mentor, context, OTHERS);
        }
        verify(realtime, never()).publishMessage(anyLong(), anyString(), any(), any());
        verify(realtime, never()).publishFile(anyLong(), anyString(), anyString(), anyString());
        verify(realtime, never()).publishTyping(anyLong(), anyString(), anyBoolean());
        verify(realtime, never()).publishPresence(anyLong(), anyString(), anyString(), any());
        verify(presence, never()).joinGroup(anyString(), anyLong(), anyString());
    }

    @Test
    void aMentorsSocketCantSendToATeamOfOne() {
        send(mentor, "sendMessage", SOLO);
        send(mentor, "sendFile", SOLO);
        verify(realtime, never()).publishMessage(anyLong(), anyString(), any(), any());
        verify(realtime, never()).publishFile(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void aMentorsSocketWorksInTheirTeamAndCanAlwaysLeave() {
        send(mentor, "sendMessage", TEAM);
        verify(realtime, times(1)).publishMessage(anyLong(), anyString(), any(), any());
        send(mentor, "leaveGroup", OTHERS);
        verify(presence).leaveGroup("session-3", OTHERS);
    }

    @Test
    void aSignInThatIsntAMentorsLoadsNoOne() {
        Person mentoringTeacher = MentorRulesTest.person(6, "ROLE_USER", MentorRules.ROLE_MENTOR, "ROLE_TEACHER");
        for (String context : List.of("typingStart", "heartbeat", "sendMessage")) {
            sendSignedIn(student, context, OTHERS);
            sendSignedIn(mentoringTeacher, context, OTHERS);
        }
        verify(people, never()).findByUid(anyString());
        verify(groups, never()).findById(anyLong());
        verify(realtime, times(2)).publishMessage(eq(OTHERS), anyString(), any(), any());
    }

    @Test
    void aMentorsSignInIsStillCheckedAgainstTheGroup() {
        sendSignedIn(mentor, "sendMessage", OTHERS);
        sendSignedIn(mentor, "sendMessage", TEAM);
        verify(realtime, never()).publishMessage(eq(OTHERS), anyString(), any(), any());
        verify(realtime, times(1)).publishMessage(eq(TEAM), anyString(), any(), any());
    }

    @Test
    void studentsSocketsSendAsBefore() {
        send(student, "sendMessage", OTHERS);
        send(student, "sendMessage", SOLO);
        verify(realtime, times(2)).publishMessage(anyLong(), anyString(), any(), any());
        verify(groups, never()).findMembersOf(anyLong());
    }
}
