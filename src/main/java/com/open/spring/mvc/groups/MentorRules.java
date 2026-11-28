package com.open.spring.mvc.groups;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.open.spring.mvc.person.Person;

/**
 * What a mentor may do in chat. A mentor is an account with ROLE_MENTOR and no teacher or admin role: Flask
 * verifies mentors, and Spring gives the role at login when Flask's signed statement says so.
 *
 * A mentor never talks with a student privately. So no direct message that includes a mentor can be started, sent
 * or edited, and a mentor uses only a group a team added them to, with at least MIN_TEAM_SIZE members who aren't
 * mentors, never a class group. The team manages its mentors: only a member who isn't a mentor, or a teacher, adds or
 * removes one. A mentor adds no one, and can't look through or change groups. Everyone else keeps the answers they
 * had before these rules.
 *
 * Every method is static and decides only from what it is given, like Java's Math. The controllers load the people
 * involved and ask; the rules stay in this one place.
 */
public final class MentorRules {

    /** The role Spring gives an account that Flask says is a verified mentor. */
    public static final String ROLE_MENTOR = "ROLE_MENTOR";

    /** The fewest members who aren't mentors that a group needs before a mentor can be in it. */
    public static final int MIN_TEAM_SIZE = 2;

    /** Why a mentor rule refused a request. Each one is answered with 403 and its reason. */
    public enum Refusal {
        NO_DIRECT_MESSAGES("mentor_no_direct_messages",
                "Mentors can't send or receive direct messages. Talk with a team in its group."),
        NOT_IN_GROUP("mentor_not_in_group",
                "This chat is for the group's members and its mentors."),
        TEAM_TOO_SMALL("mentor_team_too_small",
                "A mentor can only be in a group with at least " + MIN_TEAM_SIZE + " members who aren't mentors."),
        CLASS_GROUP("mentor_class_group",
                "Mentors can't be in class groups."),
        CANNOT_MANAGE("mentor_cannot_manage",
                "Mentors can't add people to groups, or make, change or look through groups."),
        TEAM_MANAGES_MENTORS("team_manages_mentors",
                "Only the group's members or a teacher can add or remove its mentor.");

        private final String reason;
        private final String message;

        Refusal(String reason, String message) {
            this.reason = reason;
            this.message = message;
        }

        /** The code a page shows its own words for. */
        public String reason() {
            return reason;
        }

        /** The sentence sent as the error, for anyone reading the API directly. */
        public String message() {
            return message;
        }

        /** 403 with a body of error and reason. */
        public ResponseEntity<Object> toResponse() {
            return new ResponseEntity<>(Map.of("error", message, "reason", reason), HttpStatus.FORBIDDEN);
        }
    }

    private MentorRules() {
    }

    /** True for an account with ROLE_MENTOR and neither ROLE_TEACHER nor ROLE_ADMIN. */
    public static boolean isMentor(Person person) {
        return person != null && isMentorRoles(person.getRoles());
    }

    /** The same question asked of a list of role names, for rows read without a Person. */
    public static boolean isMentorRoles(Collection<String> roles) {
        return roles != null && roles.contains(ROLE_MENTOR) && !isStaffRoles(roles);
    }

    /** True for a teacher or an admin. */
    public static boolean isStaff(Person person) {
        return person != null && isStaffRoles(person.getRoles());
    }

    private static boolean isStaffRoles(Collection<String> roles) {
        return roles != null && (roles.contains("ROLE_TEACHER") || roles.contains("ROLE_ADMIN"));
    }

    /** How many different people among the members aren't mentors; someone listed twice counts once. */
    public static long teamSize(Collection<Person> members) {
        return members.stream().filter(member -> !isMentor(member)).map(Person::getId).distinct().count();
    }

    /**
     * Whether a person is one of the members. Compared by id: Person's equals compares every field, so the same
     * person loaded twice may not be equal.
     */
    public static boolean isMember(Person person, Collection<Person> members) {
        return person != null && person.getId() != null
                && members.stream().anyMatch(member -> Objects.equals(member.getId(), person.getId()));
    }

    /** Refuses a direct message that would include a mentor, whoever is sending it. */
    public static Optional<Refusal> shareDirectMessages(Collection<Person> people) {
        return people.stream().anyMatch(MentorRules::isMentor)
                ? Optional.of(Refusal.NO_DIRECT_MESSAGES)
                : Optional.empty();
    }

    /**
     * Whether a person may read a group, post in its chat and share its files. A mentor needs to be a member of a
     * group that isn't a class group and has at least MIN_TEAM_SIZE members who aren't mentors.
     */
    public static Optional<Refusal> useGroup(Person person, boolean classGroup, Collection<Person> members) {
        if (!isMentor(person)) {
            return Optional.empty();
        }
        if (!isMember(person, members)) {
            return Optional.of(Refusal.NOT_IN_GROUP);
        }
        if (classGroup) {
            return Optional.of(Refusal.CLASS_GROUP);
        }
        return teamSize(members) < MIN_TEAM_SIZE ? Optional.of(Refusal.TEAM_TOO_SMALL) : Optional.empty();
    }

    /**
     * Whether actor may add newMember to a group with these members. A mentor adds no one. Adding a mentor needs a
     * teacher, or a member who isn't a mentor, and a group a mentor may use.
     */
    public static Optional<Refusal> addMember(
            Person actor, Person newMember, boolean classGroup, Collection<Person> members) {
        if (isMentor(actor)) {
            return Optional.of(Refusal.CANNOT_MANAGE);
        }
        if (!isMentor(newMember)) {
            return Optional.empty();
        }
        if (!isStaff(actor) && !isMember(actor, members)) {
            return Optional.of(Refusal.TEAM_MANAGES_MENTORS);
        }
        if (classGroup) {
            return Optional.of(Refusal.CLASS_GROUP);
        }
        return teamSize(members) < MIN_TEAM_SIZE ? Optional.of(Refusal.TEAM_TOO_SMALL) : Optional.empty();
    }

    /**
     * Whether actor may take member out of a group with these members. A mentor may only leave. Removing a mentor
     * needs a teacher or a member who isn't a mentor. Leaving is never refused to anyone else.
     */
    public static Optional<Refusal> removeMember(Person actor, Person member, Collection<Person> members) {
        boolean leaving = actor != null && member != null && Objects.equals(actor.getId(), member.getId());
        if (isMentor(actor)) {
            return leaving ? Optional.empty() : Optional.of(Refusal.CANNOT_MANAGE);
        }
        if (!isMentor(member) || isStaff(actor) || isMember(actor, members)) {
            return Optional.empty();
        }
        return Optional.of(Refusal.TEAM_MANAGES_MENTORS);
    }

    /** Whether a new group may start with these members: each mentor in it must be one the actor could add. */
    public static Optional<Refusal> startGroup(Person actor, boolean classGroup, Collection<Person> members) {
        Optional<Refusal> refusal = manageGroups(actor);
        if (refusal.isPresent()) {
            return refusal;
        }
        return members.stream()
                .filter(MentorRules::isMentor)
                .map(mentor -> addMember(actor, mentor, classGroup, members))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Refuses a mentor the routes that look through or change groups: every group, search, making, renaming and
     * deleting groups, group grades, and another person's groups.
     */
    public static Optional<Refusal> manageGroups(Person person) {
        return isMentor(person) ? Optional.of(Refusal.CANNOT_MANAGE) : Optional.empty();
    }
}
