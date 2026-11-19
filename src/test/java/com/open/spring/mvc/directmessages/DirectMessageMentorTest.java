package com.open.spring.mvc.directmessages;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.MentorRules;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Mentors and direct messages: a mentor can't open a direct message with a student or post in one, and a student
 * can't message a mentor directly. Reading old messages and deleting your own still work, so the navbar bell and
 * /dm keep answering.
 */
class DirectMessageMentorTest {

    private DirectMessageService service;
    private DirectMessageConversationJpaRepository conversations;
    private PersonJpaRepository people;
    private DirectMessageApiController controller;

    private final Person student = person(1L, "student", "ROLE_USER", "ROLE_STUDENT");
    private final Person otherStudent = person(2L, "other", "ROLE_USER", "ROLE_STUDENT");
    private final Person mentor = person(3L, "mentor", "ROLE_USER", MentorRules.ROLE_MENTOR);
    private final Person teacher = person(4L, "teacher", "ROLE_USER", "ROLE_TEACHER");

    private static Person person(Long id, String uid, String... roles) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        person.setRoles(new ArrayList<>(List.of(roles)));
        return person;
    }

    private static UserDetails signedIn(Person person) {
        return User.withUsername(person.getUid()).password("unused").authorities("ROLE_USER").build();
    }

    private DirectMessageConversation conversation(long id, Person... participants) {
        DirectMessageConversation conversation = new DirectMessageConversation(new ArrayList<>(List.of(participants)));
        ReflectionTestUtils.setField(conversation, "id", id);
        when(conversations.findById(id)).thenReturn(Optional.of(conversation));
        when(service.isParticipant(any(), any())).thenCallRealMethod();
        return conversation;
    }

    @BeforeEach
    void setUp() {
        service = mock(DirectMessageService.class);
        conversations = mock(DirectMessageConversationJpaRepository.class);
        people = mock(PersonJpaRepository.class);
        controller = new DirectMessageApiController(
                service, conversations, mock(DirectMessageJpaRepository.class), people);
        for (Person person : List.of(student, otherStudent, mentor, teacher)) {
            when(people.findByUid(person.getUid())).thenReturn(person);
            when(people.findById(person.getId())).thenReturn(Optional.of(person));
        }
        when(service.getUnreadSummary(any())).thenReturn(new DirectMessageService.UnreadSummary(List.of(), Map.of()));
    }

    private static void assertRefused(ResponseEntity<Object> response) {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("mentor_no_direct_messages", ((Map<?, ?>) response.getBody()).get("reason"));
    }

    private ResponseEntity<Object> start(Person from, Person... to) {
        List<Long> ids = new ArrayList<>();
        for (Person person : to) {
            ids.add(person.getId());
        }
        return controller.startConversation(signedIn(from),
                new DirectMessageApiController.StartConversationRequest(ids));
    }

    private ResponseEntity<Object> post(Person from, long conversationId) {
        return controller.postMessage(signedIn(from), conversationId,
                new DirectMessageApiController.SendMessageRequest("hi", null));
    }

    @Test
    void aMentorCannotOpenADirectMessageWithAStudent() {
        assertRefused(start(mentor, student));
        verify(service, never()).getOrCreateConversation(any(), any());
    }

    @Test
    void aStudentCannotOpenADirectMessageWithAMentor() {
        assertRefused(start(student, otherStudent, mentor));
        verify(service, never()).getOrCreateConversation(any(), any());
    }

    @Test
    void aMentorCannotPostToADirectMessageWithAStudent() {
        conversation(10L, mentor, student);
        assertRefused(post(mentor, 10L));
        verify(service, never()).postMessage(any(), any(), anyString(), any());
    }

    @Test
    void aStudentCannotPostToAConversationThatIncludesAMentor() {
        conversation(11L, student, otherStudent, mentor);
        assertRefused(post(student, 11L));
        verify(service, never()).postMessage(any(), any(), anyString(), any());
    }

    @Test
    void editingInAConversationWithAMentorIsRefused() {
        conversation(12L, student, mentor);
        assertRefused(controller.editMessage(signedIn(student), 12L, 5L,
                new DirectMessageApiController.SendMessageRequest("changed", null)));
        verify(service, never()).editMessage(any(), any(), anyLong(), anyString());
    }

    @Test
    void readingAnOldConversationWithAMentorStillWorks() {
        conversation(13L, student, mentor);
        when(service.getMessages(any())).thenReturn(List.of());
        assertEquals(HttpStatus.OK, controller.getMessages(signedIn(student), 13L).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getMessages(signedIn(mentor), 13L).getStatusCode());
    }

    @Test
    void deletingYourOwnMessageStillWorks() {
        conversation(14L, student, mentor);
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteMessage(signedIn(student), 14L, 5L).getStatusCode());
    }

    @Test
    void studentsAndTeachersStillMessageEachOther() {
        DirectMessageConversation existing = conversation(15L, student, teacher);
        when(service.getOrCreateConversation(any(), any())).thenReturn(existing);
        when(service.postMessage(any(), any(), anyString(), any())).thenReturn(null);
        assertEquals(HttpStatus.OK, start(student, teacher).getStatusCode());
        assertEquals(HttpStatus.OK, post(teacher, 15L).getStatusCode());
    }
}
