package com.open.spring.mvc.person;

/**
 * Thrown by PersonDetailsService when a new password breaks Person.PASSWORD_RULE.
 * ExceptionHandlerAdvice turns it into a 400 with {"error": PASSWORD_RULE}, so
 * controllers don't need their own password checks.
 */
public class WeakPasswordException extends RuntimeException {
    public WeakPasswordException() {
        super(Person.PASSWORD_RULE);
    }
}
