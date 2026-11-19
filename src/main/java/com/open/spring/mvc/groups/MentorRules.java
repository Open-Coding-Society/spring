package com.open.spring.mvc.groups;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.open.spring.mvc.person.Person;

/**
 * What a mentor may do in chat. A mentor is an account with ROLE_MENTOR and no teacher or admin role: Flask
 * verifies mentors, and Spring gives the role at login when Flask's signed statement says so. A mentor never talks
 * with a student privately, so no direct message that includes a mentor can be started, sent or edited.
 *
 * Every method is static and decides only from what it is given, like Java's Math. The controllers load the people
 * involved and ask; the rules stay in this one place.
 */
public final class MentorRules {

    /** The role Spring gives an account that Flask says is a verified mentor. */
    public static final String ROLE_MENTOR = "ROLE_MENTOR";

    /** Why a mentor rule refused a request. Each one is answered with 403 and its reason. */
    public enum Refusal {
        NO_DIRECT_MESSAGES("mentor_no_direct_messages",
                "Mentors can't send or receive direct messages. Talk with a team in its group.");

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
        return person != null && person.hasRole(ROLE_MENTOR) && !isStaff(person);
    }

    /** True for a teacher or an admin. */
    public static boolean isStaff(Person person) {
        return person != null && (person.hasRole("ROLE_TEACHER") || person.hasRole("ROLE_ADMIN"));
    }

    /** Refuses a direct message that would include a mentor, whoever is sending it. */
    public static Optional<Refusal> shareDirectMessages(Collection<Person> people) {
        return people.stream().anyMatch(MentorRules::isMentor)
                ? Optional.of(Refusal.NO_DIRECT_MESSAGES)
                : Optional.empty();
    }
}
