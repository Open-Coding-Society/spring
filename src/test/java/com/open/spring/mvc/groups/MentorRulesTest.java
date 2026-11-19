package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;

/**
 * MentorRules: who counts as a mentor, and what a mentor may do in chat. A mentor never talks with a student
 * privately: not in a direct message.
 */
class MentorRulesTest {

    static Person person(long id, String... roles) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid("person" + id);
        person.setName("Person " + id);
        person.setRoles(new ArrayList<>(List.of(roles)));
        return person;
    }

    static Person student(long id) {
        return person(id, "ROLE_USER", "ROLE_STUDENT");
    }

    static Person mentor(long id) {
        return person(id, "ROLE_USER", MentorRules.ROLE_MENTOR);
    }

    static Person teacher(long id) {
        return person(id, "ROLE_USER", "ROLE_TEACHER");
    }

    @Test
    void aMentorIsAnAccountWithTheMentorRoleAndNoStaffRole() {
        assertTrue(MentorRules.isMentor(mentor(1)));
        assertFalse(MentorRules.isMentor(person(2, "ROLE_USER", MentorRules.ROLE_MENTOR, "ROLE_TEACHER")));
        assertFalse(MentorRules.isMentor(person(3, "ROLE_USER", MentorRules.ROLE_MENTOR, "ROLE_ADMIN")));
        assertFalse(MentorRules.isMentor(student(4)));
        assertFalse(MentorRules.isMentor(null));
    }

    @Test
    void staffAreTeachersAndAdmins() {
        assertTrue(MentorRules.isStaff(teacher(1)));
        assertTrue(MentorRules.isStaff(person(2, "ROLE_USER", "ROLE_ADMIN")));
        assertFalse(MentorRules.isStaff(student(3)));
        assertFalse(MentorRules.isStaff(mentor(4)));
        assertFalse(MentorRules.isStaff(null));
    }

    @Test
    void directMessagesWithAMentorAreRefused() {
        Optional<MentorRules.Refusal> refusal = MentorRules.shareDirectMessages(List.of(student(1), mentor(2)));
        assertEquals(Optional.of(MentorRules.Refusal.NO_DIRECT_MESSAGES), refusal);
        assertEquals("mentor_no_direct_messages", refusal.get().reason());
    }

    @Test
    void directMessagesBetweenStudentsAndTeachersAreAllowed() {
        assertEquals(Optional.empty(), MentorRules.shareDirectMessages(List.of(student(1), student(2), teacher(3))));
    }

    @Test
    void aRefusalAnswers403WithItsReason() {
        ResponseEntity<Object> response = MentorRules.Refusal.NO_DIRECT_MESSAGES.toResponse();
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of(
                "error", MentorRules.Refusal.NO_DIRECT_MESSAGES.message(),
                "reason", "mentor_no_direct_messages"), response.getBody());
    }
}
