package com.open.spring.mvc.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

public class EmailNotificationServiceTest {

    @Mock
    private PersonJpaRepository personRepository;

    @InjectMocks
    private EmailNotificationService emailNotificationService;

    private AutoCloseable mocks;

    @BeforeEach
    public void setup() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void resolveRecipientEmailPrefersLoggedInPersonEmail() {
        Person person = new Person();
        person.setEmail("alice@example.com");
        when(personRepository.findByUid("alice")).thenReturn(person);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "ignored", List.of()));

        assertEquals("alice@example.com", emailNotificationService.resolveRecipientEmail());
    }
}