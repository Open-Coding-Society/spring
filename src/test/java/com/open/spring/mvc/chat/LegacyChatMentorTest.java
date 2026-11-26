package com.open.spring.mvc.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.open.spring.mvc.groups.ClassGroupMembershipService;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.MentorAccess;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * The older /api/chat/{groupId} keeps its own chat file per group. Only the group's members, teachers and admins read it,
 * and only members post. A mentor uses it only where they may use the group's chat (MentorRules).
 */
class LegacyChatMentorTest {

    private ChatService chat;
    private GroupsJpaRepository groups;
    private ClassGroupMembershipService classGroups;
    private PersonJpaRepository people;
    private ChatApiController controller;

    private static Person person(long id, String... roles) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid("person" + id);
        person.setName("Person " + id);
        person.setRoles(new ArrayList<>(List.of(roles)));
        return person;
    }

    private final Person student = person(1, "ROLE_USER", "ROLE_STUDENT");
    private final Person teammate = person(2, "ROLE_USER", "ROLE_STUDENT");
    private final Person mentor = person(3, "ROLE_USER", "ROLE_MENTOR");
    private final Person outsider = person(5, "ROLE_USER", "ROLE_STUDENT");
    private final Person teacher = person(8, "ROLE_USER", "ROLE_TEACHER");
    private final Person admin = person(9, "ROLE_USER", "ROLE_ADMIN");

    private static final long OTHERS = 7L;
    private static final long SOLO = 8L;
    private static final long TEAM = 9L;

    private void group(long id, String name, Person... members) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        for (Person member : members) {
            group.getGroupMembers().add(member);
        }
        when(groups.findById(id)).thenReturn(Optional.of(group));
        when(groups.findMembersOf(id)).thenReturn(List.of(members));
    }

    @BeforeEach
    void setUp() {
        chat = mock(ChatService.class);
        groups = mock(GroupsJpaRepository.class);
        classGroups = mock(ClassGroupMembershipService.class);
        people = mock(PersonJpaRepository.class);
        controller = new ChatApiController(chat, groups, people, new MentorAccess(groups, classGroups));
        for (Person person : List.of(student, teammate, mentor, outsider, teacher, admin)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
        }
        group(OTHERS, "Team Rocket", student, teammate);
        group(SOLO, "Just Us", student, mentor);
        group(TEAM, "Team Plasma", student, teammate, mentor);
        when(chat.getChatHistory(anyLong())).thenReturn(List.of());
        when(chat.addMessage(anyLong(), any())).thenReturn(List.of());
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

    private static ChatApiController.ChatMessageRequest text(String content) {
        return new ChatApiController.ChatMessageRequest(content);
    }

    @Test
    void aMentorWhoIsNotInTheGroupCantReadItsOlderChat() {
        signIn(mentor);
        assertRefused("mentor_not_in_group", controller.getChat(OTHERS));
        verify(chat, never()).getChatHistory(anyLong());
    }

    @Test
    void aMentorWithATeamOfOneCantUseItsOlderChat() {
        signIn(mentor);
        assertRefused("mentor_team_too_small", controller.getChat(SOLO));
        assertRefused("mentor_team_too_small", controller.postChat(SOLO, text("just us")));
        verify(chat, never()).addMessage(anyLong(), any());
    }

    @Test
    void aMentorInTheirTeamUsesItsOlderChat() {
        signIn(mentor);
        assertEquals(HttpStatus.OK, controller.getChat(TEAM).getStatusCode());
        assertEquals(HttpStatus.OK, controller.postChat(TEAM, text("hi team")).getStatusCode());
    }

    @Test
    void someoneOutsideTheGroupCantReadItsOlderChat() {
        signIn(outsider);
        assertEquals(HttpStatus.FORBIDDEN, controller.getChat(OTHERS).getStatusCode());
        verify(chat, never()).getChatHistory(anyLong());
    }

    @Test
    void membersTeachersAndAdminsReadTheOlderChat() {
        for (Person reader : List.of(student, teacher, admin)) {
            signIn(reader);
            assertEquals(HttpStatus.OK, controller.getChat(OTHERS).getStatusCode(), reader.getUid());
        }
        verify(groups, never()).findMembersOf(anyLong());
    }

    @Test
    void onlyMembersPostAsBefore() {
        signIn(outsider);
        assertEquals(HttpStatus.FORBIDDEN, controller.postChat(OTHERS, text("hi")).getStatusCode());
        signIn(student);
        assertEquals(HttpStatus.OK, controller.postChat(OTHERS, text("hi")).getStatusCode());
        verify(groups, never()).findMembersOf(anyLong());
    }
}
