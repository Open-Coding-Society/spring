package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.synergy.SynergyGrade;
import com.open.spring.mvc.synergy.SynergyGradeJpaRepository;

/** The retry around the gradebook copy; the copy itself is covered by the SQLite test. */
class FinalGradeTransferTest {

    private final AssignmentSubmissionJPA submissionRepo = mock(AssignmentSubmissionJPA.class);
    private final AssignmentJpaRepository assignmentRepo = mock(AssignmentJpaRepository.class);
    private final SynergyGradeJpaRepository gradebookRepo = mock(SynergyGradeJpaRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FinalGradeTransfer transfer =
            new FinalGradeTransfer(submissionRepo, assignmentRepo, gradebookRepo, transactionManager);

    private final Assignment pilot = assignment(10L, "pilot");
    private final Person hop = student(5L, "hop");

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AssignmentSubmission graded = submission(100L, pilot);
        graded.setSubmitter(hop);
        graded.setGrade(0.9);
        when(submissionRepo.findByAssignmentId(10L)).thenReturn(List.of(graded));
        when(gradebookRepo.findByAssignmentIdAndStudentId(10L, 5L)).thenReturn(Optional.empty());
    }

    @Test
    void aBusyDatabaseIsRetriedUntilTheCopySucceeds() {
        CannotAcquireLockException busy = new CannotAcquireLockException("SQLITE_BUSY");
        when(assignmentRepo.findById(10L)).thenThrow(busy, busy).thenReturn(Optional.of(pilot));

        assertEquals(1, transfer.transfer(10L));

        verify(assignmentRepo, times(FinalGradeTransfer.ATTEMPTS)).findById(10L);
        ArgumentCaptor<SynergyGrade> saved = ArgumentCaptor.forClass(SynergyGrade.class);
        verify(gradebookRepo).save(saved.capture());
        assertEquals(0.9, saved.getValue().getGrade());
    }

    @Test
    void itGivesUpAfterTheLastAttempt() {
        when(assignmentRepo.findById(10L)).thenThrow(new CannotAcquireLockException("SQLITE_BUSY"));

        assertThrows(CannotAcquireLockException.class, () -> transfer.transfer(10L));

        verify(assignmentRepo, times(FinalGradeTransfer.ATTEMPTS)).findById(10L);
    }
}
