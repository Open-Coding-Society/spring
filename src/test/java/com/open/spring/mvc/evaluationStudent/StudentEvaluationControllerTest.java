package com.open.spring.mvc.evaluationStudent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Who can reach a student evaluation on /api/student-evaluation. */
class StudentEvaluationControllerTest {

    private static final int OWN_ID = 7;
    private static final int OTHER_ID = 8;

    @Mock
    private StudentEvaluationRepository evaluationRepo;

    @Mock
    private PersonJpaRepository personRepo;

    private StudentEvaluationController controller;
    private UserDetails student;
    private UserDetails teacher;
    private StudentEvaluation ownEval;
    private StudentEvaluation otherEval;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new StudentEvaluationController();
        ReflectionTestUtils.setField(controller, "studentEvaluationRepository", evaluationRepo);
        ReflectionTestUtils.setField(controller, "personJpaRepository", personRepo);

        student = User.withUsername("stu").password("x").authorities("ROLE_USER", "ROLE_STUDENT").build();
        teacher = User.withUsername("teach").password("x").authorities("ROLE_USER", "ROLE_TEACHER").build();
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", (long) OWN_ID); // no setter on the Submitter superclass
        when(personRepo.findByUid("stu")).thenReturn(person);

        ownEval = evaluation(OWN_ID);
        otherEval = evaluation(OTHER_ID);
        when(evaluationRepo.findByUserId(OWN_ID)).thenReturn(Optional.of(ownEval));
        when(evaluationRepo.findByUserId(OTHER_ID)).thenReturn(Optional.of(otherEval));
        when(evaluationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void studentReadsOwnEvaluation() {
        assertEquals(200, controller.getEvaluationByUserId(student, OWN_ID).getStatusCode().value());
    }

    @Test
    void studentCannotReadAnotherStudentsEvaluation() {
        assertEquals(403, controller.getEvaluationByUserId(student, OTHER_ID).getStatusCode().value());
    }

    @Test
    void studentCannotUpdateAnotherStudentsEvaluation() {
        assertEquals(403, controller.updateEvaluation(student, OTHER_ID, request(null)).getStatusCode().value());
        verify(evaluationRepo, never()).save(any());
    }

    @Test
    void studentCannotMoveOwnEvaluationToAnotherUser() {
        assertEquals(403, controller.updateEvaluation(student, OWN_ID, request(OTHER_ID)).getStatusCode().value());
        assertEquals(OWN_ID, ownEval.getUserId());
        verify(evaluationRepo, never()).save(any());
    }

    @Test
    void studentUpdatesOwnEvaluation() {
        assertEquals(200, controller.updateEvaluation(student, OWN_ID, request(null)).getStatusCode().value());
        assertEquals(4.0, ownEval.getAttendance());
    }

    @Test
    void studentCannotCreateForAnotherStudent() {
        when(evaluationRepo.findByUserId(9)).thenReturn(Optional.empty());
        assertEquals(403, controller.createEvaluation(student, request(9)).getStatusCode().value());
        verify(evaluationRepo, never()).save(any());
    }

    @Test
    void studentCannotDeleteAnotherStudentsEvaluation() {
        assertEquals(403, controller.deleteEvaluation(student, OTHER_ID).getStatusCode().value());
        verify(evaluationRepo, never()).deleteById(any());
    }

    @Test
    void teacherReadsAndUpdatesAnyEvaluation() {
        assertEquals(200, controller.getEvaluationByUserId(teacher, OTHER_ID).getStatusCode().value());
        assertEquals(200, controller.updateEvaluation(teacher, OTHER_ID, request(null)).getStatusCode().value());
    }

    @Test
    void noCallerIsForbidden() {
        assertEquals(403, controller.getEvaluationByUserId(null, OWN_ID).getStatusCode().value());
    }
}
