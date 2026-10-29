package com.open.spring.mvc.evaluationStudent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.security.PersonAccess;

/** Who can reach a student evaluation, checked through the real @PreAuthorize rules. */
@SpringJUnitConfig(StudentEvaluationAccessTest.Config.class)
class StudentEvaluationAccessTest {

    private static final int OWN_ID = 7;
    private static final int OTHER_ID = 8;

    @Configuration
    @EnableMethodSecurity
    @Import({StudentEvaluationController.class, PersonAccess.class})
    static class Config {
        @Bean
        StudentEvaluationRepository studentEvaluationRepository() {
            return mock(StudentEvaluationRepository.class);
        }

        @Bean
        PersonJpaRepository personJpaRepository() {
            return mock(PersonJpaRepository.class);
        }
    }

    @Autowired
    private StudentEvaluationController controller;
    @Autowired
    private StudentEvaluationRepository evaluationRepo;
    @Autowired
    private PersonJpaRepository personRepo;

    private StudentEvaluation ownEval;

    @BeforeEach
    void setUp() {
        reset(evaluationRepo, personRepo);
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", (long) OWN_ID); // no setter on the Submitter superclass
        when(personRepo.findByUid("stu")).thenReturn(person);

        ownEval = evaluation(OWN_ID);
        when(evaluationRepo.findByUserId(OWN_ID)).thenReturn(Optional.of(ownEval));
        when(evaluationRepo.findByUserId(OTHER_ID)).thenReturn(Optional.of(evaluation(OTHER_ID)));
        when(evaluationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String uid, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(uid, "x", AuthorityUtils.createAuthorityList(roles)));
    }

    private static StudentEvaluation evaluation(int userId) {
        StudentEvaluation e = new StudentEvaluation(userId, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        e.setId((long) userId * 100);
        return e;
    }

    private static StudentEvaluationController.StudentEvaluationRequest request(Integer userId) {
        StudentEvaluationController.StudentEvaluationRequest r = new StudentEvaluationController.StudentEvaluationRequest();
        r.setUserId(userId);
        r.setAttendance(4.0);
        return r;
    }

    @Test
    void studentReadsAndUpdatesOwnEvaluation() {
        signInAs("stu", "ROLE_USER", "ROLE_STUDENT");
        assertEquals(200, controller.getEvaluationByUserId(OWN_ID).getStatusCode().value());
        assertEquals(200, controller.updateEvaluation(OWN_ID, request(null)).getStatusCode().value());
        assertEquals(4.0, ownEval.getAttendance());
    }

    @Test
    void studentCannotReachAnotherStudentsEvaluation() {
        signInAs("stu", "ROLE_USER", "ROLE_STUDENT");
        assertThrows(AccessDeniedException.class, () -> controller.getEvaluationByUserId(OTHER_ID));
        assertThrows(AccessDeniedException.class, () -> controller.updateEvaluation(OTHER_ID, request(null)));
        assertThrows(AccessDeniedException.class, () -> controller.createEvaluation(request(OTHER_ID)));
        assertThrows(AccessDeniedException.class, () -> controller.deleteEvaluation(OTHER_ID));
        verify(evaluationRepo, never()).save(any());
        verify(evaluationRepo, never()).deleteById(any());
    }

    @Test
    void studentCannotMoveOwnEvaluationToAnotherUser() {
        signInAs("stu", "ROLE_USER", "ROLE_STUDENT");
        assertThrows(AccessDeniedException.class, () -> controller.updateEvaluation(OWN_ID, request(OTHER_ID)));
        assertEquals(OWN_ID, ownEval.getUserId());
    }

    @Test
    void teacherAndAdminReachAnyEvaluation() {
        signInAs("teach", "ROLE_USER", "ROLE_TEACHER");
        assertEquals(200, controller.getEvaluationByUserId(OTHER_ID).getStatusCode().value());
        assertEquals(200, controller.updateEvaluation(OTHER_ID, request(null)).getStatusCode().value());
        signInAs("admin", "ROLE_ADMIN");
        assertEquals(200, controller.getEvaluationByUserId(OTHER_ID).getStatusCode().value());
    }
}
