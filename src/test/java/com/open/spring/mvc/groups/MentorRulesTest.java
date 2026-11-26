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

    // --- Groups: a mentor uses only the team groups they were added to ---

    private static final boolean CLASS_GROUP = true;
    private static final boolean TEAM_GROUP = false;

    private static Optional<MentorRules.Refusal> refused(MentorRules.Refusal refusal) {
        return Optional.of(refusal);
    }

    @Test
    void theTeamSizeCountsMembersWhoAreNotMentors() {
        assertEquals(2, MentorRules.teamSize(List.of(student(1), teacher(2), mentor(3))));
        assertEquals(0, MentorRules.teamSize(List.of(mentor(3))));
    }

    @Test
    void aRepeatedMemberCountsOnce() {
        Person mentor = mentor(3);
        List<Person> sameStudentTwice = List.of(student(1), student(1), mentor);
        assertEquals(1, MentorRules.teamSize(sameStudentTwice));
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL), MentorRules.useGroup(mentor, TEAM_GROUP, sameStudentTwice));
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL), MentorRules.startGroup(student(1), TEAM_GROUP, sameStudentTwice));
    }

    @Test
    void membershipIsByIdNotByObject() {
        Person loadedOnce = student(1);
        Person loadedAgain = student(1);
        loadedAgain.setName("Renamed since");
        assertTrue(MentorRules.isMember(loadedOnce, List.of(loadedAgain, student(2))));
        assertFalse(MentorRules.isMember(student(3), List.of(loadedAgain, student(2))));
        assertFalse(MentorRules.isMember(null, List.of(student(2))));
    }

    @Test
    void aMemberMentorWithATeamOfTwoCanUseTheGroup() {
        Person mentor = mentor(3);
        assertEquals(Optional.empty(), MentorRules.useGroup(mentor, TEAM_GROUP, List.of(student(1), student(2), mentor)));
    }

    @Test
    void aMentorWhoIsNotAMemberIsRefused() {
        assertEquals(refused(MentorRules.Refusal.NOT_IN_GROUP),
                MentorRules.useGroup(mentor(3), TEAM_GROUP, List.of(student(1), student(2))));
    }

    @Test
    void aMentorInAClassGroupIsRefused() {
        Person mentor = mentor(3);
        assertEquals(refused(MentorRules.Refusal.CLASS_GROUP),
                MentorRules.useGroup(mentor, CLASS_GROUP, List.of(student(1), student(2), mentor)));
    }

    @Test
    void aMentorLosesTheGroupWhenTheTeamDropsBelowTwo() {
        Person mentor = mentor(3);
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL),
                MentorRules.useGroup(mentor, TEAM_GROUP, List.of(student(1), mentor)));
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL),
                MentorRules.useGroup(mentor, TEAM_GROUP, List.of(student(1), mentor(4), mentor)));
    }

    @Test
    void studentsAndTeachersUseGroupsAsBefore() {
        assertEquals(Optional.empty(), MentorRules.useGroup(student(1), TEAM_GROUP, List.of(student(2))));
        assertEquals(Optional.empty(), MentorRules.useGroup(teacher(1), CLASS_GROUP, List.of()));
    }

    @Test
    void aTeamMemberCanAddAMentor() {
        Person member = student(1);
        assertEquals(Optional.empty(),
                MentorRules.addMember(member, mentor(3), TEAM_GROUP, List.of(member, student(2))));
    }

    @Test
    void aStudentOutsideTheTeamCannotAddAMentor() {
        assertEquals(refused(MentorRules.Refusal.TEAM_MANAGES_MENTORS),
                MentorRules.addMember(student(9), mentor(3), TEAM_GROUP, List.of(student(1), student(2))));
    }

    @Test
    void aMentorCannotAddAnyoneNotEvenThemselves() {
        Person mentor = mentor(3);
        List<Person> team = List.of(student(1), student(2));
        assertEquals(refused(MentorRules.Refusal.CANNOT_MANAGE), MentorRules.addMember(mentor, mentor, TEAM_GROUP, team));
        assertEquals(refused(MentorRules.Refusal.CANNOT_MANAGE),
                MentorRules.addMember(mentor, student(5), TEAM_GROUP, team));
    }

    @Test
    void aTeacherCanAddAMentorToAnyTeamGroup() {
        assertEquals(Optional.empty(),
                MentorRules.addMember(teacher(8), mentor(3), TEAM_GROUP, List.of(student(1), student(2))));
    }

    @Test
    void aMentorCannotJoinAOneStudentGroup() {
        Person member = student(1);
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL),
                MentorRules.addMember(member, mentor(3), TEAM_GROUP, List.of(member)));
        assertEquals(refused(MentorRules.Refusal.TEAM_TOO_SMALL),
                MentorRules.addMember(teacher(8), mentor(3), TEAM_GROUP, List.of(student(1))));
    }

    @Test
    void aMentorCannotJoinAClassGroup() {
        assertEquals(refused(MentorRules.Refusal.CLASS_GROUP),
                MentorRules.addMember(teacher(8), mentor(3), CLASS_GROUP, List.of(student(1), student(2))));
    }

    @Test
    void addingStudentsWorksAsBefore() {
        assertEquals(Optional.empty(), MentorRules.addMember(student(9), student(5), TEAM_GROUP, List.of(student(1))));
        assertEquals(Optional.empty(), MentorRules.addMember(student(9), student(5), CLASS_GROUP, List.of()));
    }

    @Test
    void aMentorCanLeave() {
        Person mentor = mentor(3);
        assertEquals(Optional.empty(), MentorRules.removeMember(mentor, mentor(3), List.of(student(1), mentor)));
    }

    @Test
    void aMentorCannotRemoveAStudent() {
        Person mentor = mentor(3);
        assertEquals(refused(MentorRules.Refusal.CANNOT_MANAGE),
                MentorRules.removeMember(mentor, student(1), List.of(student(1), student(2), mentor)));
    }

    @Test
    void aStudentCanAlwaysLeave() {
        Person member = student(1);
        assertEquals(Optional.empty(), MentorRules.removeMember(member, member, List.of(member, mentor(3))));
    }

    @Test
    void onlyTheTeamOrATeacherRemovesAMentor() {
        Person member = student(1);
        Person mentor = mentor(3);
        List<Person> team = List.of(member, student(2), mentor);
        assertEquals(Optional.empty(), MentorRules.removeMember(member, mentor, team));
        assertEquals(Optional.empty(), MentorRules.removeMember(teacher(8), mentor, team));
        assertEquals(refused(MentorRules.Refusal.TEAM_MANAGES_MENTORS), MentorRules.removeMember(student(9), mentor, team));
    }

    @Test
    void mentorsCannotManageGroups() {
        assertEquals(refused(MentorRules.Refusal.CANNOT_MANAGE), MentorRules.manageGroups(mentor(3)));
        assertEquals(Optional.empty(), MentorRules.manageGroups(student(1)));
        assertEquals(Optional.empty(), MentorRules.manageGroups(teacher(8)));
    }

    @Test
    void everyReasonIsDistinct() {
        long reasons = java.util.Arrays.stream(MentorRules.Refusal.values()).map(MentorRules.Refusal::reason).distinct().count();
        assertEquals(MentorRules.Refusal.values().length, reasons);
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
