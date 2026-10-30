package com.open.spring.mvc.gradecalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.Entry;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.Grades;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.InvalidSheetException;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.Sheet;
import com.open.spring.mvc.person.Person;

class GradeCalculatorServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 10, 0);

    @Mock
    private GradeCalculatorJpaRepository repository;

    @Mock
    private Person student;

    private GradeCalculatorService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new GradeCalculatorService(repository, new ObjectMapper(), clock);
    }

    // 45/50 + 16/20 = 61/70 = 87.1%. The page's grade-calculator.js must produce the same numbers.
    @Test
    void theGradeIsTotalPointsEarnedOverPointsPossible() {
        Grades grades = GradeCalculatorService.calculate(new Sheet(List.of(
                entry("Unit 1 test", 45.0, 50.0, false),
                entry("HW 1", 16.0, 20.0, false))));

        assertEquals(87.1, grades.current());
        assertEquals(87.1, grades.projected());
    }

    @Test
    void whatIfScoresCountOnlyTowardTheProjectedGrade() {
        Grades grades = GradeCalculatorService.calculate(new Sheet(List.of(
                entry("Unit 1 test", 45.0, 50.0, false),
                entry("HW 1", 16.0, 20.0, false),
                entry("Final", 40.0, 50.0, true))));

        assertEquals(87.1, grades.current());
        // 101/120 = 84.2%
        assertEquals(84.2, grades.projected());
    }

    @Test
    void assignmentsWithoutAScoreAreSkipped() {
        Grades grades = GradeCalculatorService.calculate(new Sheet(List.of(
                entry("Unit 1 test", 45.0, 50.0, false),
                entry("HW 2", null, 20.0, false))));

        assertEquals(90.0, grades.current());
    }

    @Test
    void nothingScoredMeansNoGrade() {
        Grades grades = GradeCalculatorService.calculate(new Sheet(List.of(entry("HW 1", null, 20.0, false))));

        assertNull(grades.current());
        assertNull(grades.projected());
    }

    @Test
    void saveStoresTheCleanedSheetAndLoadReadsItBack() {
        AtomicReference<GradeCalculatorEntity> stored = new AtomicReference<>();
        when(repository.findByPerson(student)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(GradeCalculatorEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        Sheet saved = service.save(student, new Sheet(List.of(entry("  Midterm ", 88.0, 100.0, null))));

        assertEquals("Midterm", saved.entries().get(0).name());
        assertEquals(false, saved.entries().get(0).whatIf());
        assertEquals(NOW, stored.get().getUpdatedAt());
        assertEquals(saved, service.load(student));
        assertEquals(Optional.of(88.0), service.currentGrade(student));
        assertEquals(Optional.of(NOW), service.updatedAt(student));
    }

    @Test
    void aSheetSavedWithCategoriesStillLoads() {
        GradeCalculatorEntity old = new GradeCalculatorEntity();
        old.setData("{\"categories\":[{\"name\":\"Tests\",\"weight\":60.0}],"
                + "\"entries\":[{\"name\":\"Quiz\",\"category\":\"Tests\",\"earned\":9.0,\"possible\":10.0,\"whatIf\":false}]}");
        when(repository.findByPerson(student)).thenReturn(Optional.of(old));

        Sheet sheet = service.load(student);

        assertEquals(List.of(entry("Quiz", 9.0, 10.0, false)), sheet.entries());
        assertEquals(Optional.of(90.0), service.currentGrade(student));
    }

    @Test
    void loadWithoutASavedSheetIsEmpty() {
        when(repository.findByPerson(student)).thenReturn(Optional.empty());

        assertTrue(service.load(student).entries().isEmpty());
        assertEquals(Optional.empty(), service.currentGrade(student));
    }

    @Test
    void saveRejectsInvalidSheets() {
        assertRejected(new Sheet(List.of(entry("Lab", 5.0, 0.0, false))), "Lab needs points possible above 0");
        assertRejected(new Sheet(List.of(entry("Lab", -1.0, 10.0, false))), "Lab has an invalid score");
        assertRejected(new Sheet(List.of(entry(" ", 1.0, 10.0, false))), "every assignment needs a name");
        Entry[] tooMany = new Entry[GradeCalculatorService.MAX_ENTRIES + 1];
        Arrays.fill(tooMany, entry("HW", 1.0, 1.0, false));
        assertRejected(new Sheet(List.of(tooMany)), "at most 300 assignments");
        verify(repository, never()).save(any());
    }

    private void assertRejected(Sheet sheet, String message) {
        InvalidSheetException e = assertThrows(InvalidSheetException.class, () -> service.save(student, sheet));
        assertEquals(message, e.getMessage());
    }

    private static Entry entry(String name, Double earned, Double possible, Boolean whatIf) {
        return new Entry(name, earned, possible, whatIf);
    }
}
