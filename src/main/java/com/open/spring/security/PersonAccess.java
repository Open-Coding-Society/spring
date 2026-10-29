package com.open.spring.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Ownership checks for @PreAuthorize expressions, so controllers keep no security code.
 * Use it as @personAccess.isSelf(authentication, #personId) next to a role check.
 */
@Component("personAccess")
public class PersonAccess {

    @Autowired
    private PersonJpaRepository personJpaRepository;

    /** True when the signed-in account is the Person with this id. */
    public boolean isSelf(Authentication authentication, Number personId) {
        if (authentication == null || personId == null) return false;
        Person person = personJpaRepository.findByUid(authentication.getName());
        return person != null && person.getId() != null && person.getId() == personId.longValue();
    }
}
