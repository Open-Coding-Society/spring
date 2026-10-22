package com.open.spring.mvc.person;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.system.ExceptionHandlerAdvice;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordCheckTest {

    @Test
    public void testStrongPassword() {
        Person person = new Person();
        person.setPassword("Password1!");

        assertTrue(person.checkPassword());
    }

    @Test
    public void testNoUppercase() {
        Person person = new Person();
        person.setPassword("password1!");

        assertFalse(person.checkPassword());
    }

    @Test
    public void testNoLowercase() {
        Person person = new Person();
        person.setPassword("PASSWORD1!");

        assertFalse(person.checkPassword());
    }

    @Test
    public void testNoNumber() {
        Person person = new Person();
        person.setPassword("Password!");

        assertFalse(person.checkPassword());
    }

    @Test
    public void testNoSpecialCharacter() {
        Person person = new Person();
        person.setPassword("Password1");

        assertFalse(person.checkPassword());
    }

    @Test
    public void testTooShort() {
        Person person = new Person();
        person.setPassword("Pa1!");

        assertFalse(person.checkPassword());
    }

    @Test
    public void testNullFailsTheRule() {
        assertFalse(Person.meetsPasswordRule(null));
    }

    @Test
    public void testStaticRuleMatchesCheckPassword() {
        for (String pw : new String[] {"Password1!", "password1!", "PASSWORD1!", "Password!", "Password1", "Pa1!"}) {
            Person person = new Person();
            person.setPassword(pw);
            assertEquals(person.checkPassword(), Person.meetsPasswordRule(pw), pw);
        }
    }

    private PersonDetailsService serviceWithEncoder() {
        PersonDetailsService service = new PersonDetailsService();
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());
        return service;
    }

    @Test
    public void testEncodeNewPasswordRejectsWeakPassword() {
        WeakPasswordException e = assertThrows(WeakPasswordException.class,
                () -> serviceWithEncoder().encodeNewPassword("password123"));
        assertEquals(Person.PASSWORD_RULE, e.getMessage());
    }

    @Test
    public void testEncodeNewPasswordEncodesStrongPassword() {
        String hash = serviceWithEncoder().encodeNewPassword("Password1!");
        assertTrue(new BCryptPasswordEncoder().matches("Password1!", hash));
    }

    @Test
    public void testWeakPasswordBecomesA400WithTheRule() throws Exception {
        ResponseEntity<String> response = new ExceptionHandlerAdvice().weakPasswordException(new WeakPasswordException());
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Person.PASSWORD_RULE, new org.json.JSONObject(response.getBody()).getString("error"));
    }
}
