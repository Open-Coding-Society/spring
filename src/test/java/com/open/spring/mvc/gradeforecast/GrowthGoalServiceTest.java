package com.open.spring.mvc.gradeforecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.gradeforecast.GradeForecastService.SkillCheck;
import com.open.spring.mvc.gradeforecast.GrowthGoalEntity.Outcome;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.GoalConflictException;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.GoalView;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.Goals;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.InvalidGoalException;
import com.open.spring.mvc.person.Person;

class GrowthGoalServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock
    private GrowthGoalJpaRepository repository;

    @Mock
    private GradeForecastService forecastService;

    @Mock
    private Person student;

    private GrowthGoalService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new GrowthGoalService(repository, forecastService, clock);
        when(repository.save(any(GrowthGoalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findFirstByPersonAndCheckedInAtIsNull(student)).thenReturn(Optional.empty());
        when(forecastService.skillCheck(student, "timeliness")).thenReturn(check(5, 3.0, 3.7));
    }

    @Test
    void startCopiesTheSkillsViewsAndDefaultsTheCheckInToTwoWeeks() {
        GoalView goal = service.start(student, "timeliness", "  Start each assignment the day it is posted ", null);

        assertEquals("timeliness", goal.skill());
        assertEquals("Start each assignment the day it is posted", goal.action());
        assertEquals(TODAY.plusDays(14), goal.checkInDate());
        assertFalse(goal.due());
        assertEquals(5, goal.startSelf());
        assertEquals(3.0, goal.startTeacher());
        assertEquals(3.7, goal.startEvidence());
        assertNull(goal.outcome());
    }

    @Test
    void aGoalCanTargetASkillThePredictorDoesNotRate() {
        GoalView goal = service.start(student, "aiOrchestration", "Test every AI suggestion before I commit it", null);

        assertEquals("aiOrchestration", goal.skill());
        assertNull(goal.startSelf());
        assertNull(goal.startTeacher());
        assertNull(goal.startEvidence());
        verify(forecastService, never()).skillCheck(student, "aiOrchestration");
    }

    @Test
    void startRejectsInvalidRequests() {
        assertInvalid(() -> service.start(student, "charisma", "Do it", null), "choose one of the listed skills");
        assertInvalid(() -> service.start(student, "timeliness", " ", null), "describe the action you will take");
        assertInvalid(() -> service.start(student, "timeliness", "x".repeat(201), null), "keep the action to 200 characters");
        assertInvalid(() -> service.start(student, "timeliness", "Plan ahead", TODAY), "pick a check-in date within the next 60 days");
        assertInvalid(() -> service.start(student, "timeliness", "Plan ahead", TODAY.plusDays(61)), "pick a check-in date within the next 60 days");
        verify(repository, never()).save(any());
    }

    @Test
    void onlyOneGoalCanBeActive() {
        when(repository.findFirstByPersonAndCheckedInAtIsNull(student)).thenReturn(Optional.of(new GrowthGoalEntity()));

        GoalConflictException e = assertThrows(GoalConflictException.class,
                () -> service.start(student, "timeliness", "Plan ahead", null));
        assertEquals("check in on your current goal before starting a new one", e.getMessage());
    }

    @Test
    void checkInRecordsTheOutcomeAndWhetherTheSkillImproved() {
        GrowthGoalEntity goal = active(1L, TODAY.minusDays(1));
        when(repository.findByIdAndPerson(1L, student)).thenReturn(Optional.of(goal));
        when(forecastService.skillCheck(student, "timeliness")).thenReturn(check(4, 4.0, 4.3));

        GoalView done = service.checkIn(student, 1L, Outcome.DID_IT, " Planned every Monday ").orElseThrow();

        assertEquals(Outcome.DID_IT, done.outcome());
        assertEquals("Planned every Monday", done.note());
        assertEquals(NOW, done.checkedInAt());
        assertEquals(4, done.endSelf());
        assertEquals(4.0, done.endTeacher());
        assertTrue(done.improved());
    }

    @Test
    void checkInNeedsAnOutcomeAndAnActiveGoalOfTheCaller() {
        assertInvalid(() -> service.checkIn(student, 1L, null, null), "say how it went");
        when(repository.findByIdAndPerson(2L, student)).thenReturn(Optional.empty());
        assertTrue(service.checkIn(student, 2L, Outcome.PARTLY, null).isEmpty());
        GrowthGoalEntity finished = active(3L, TODAY);
        finished.setCheckedInAt(NOW.minusDays(1));
        when(repository.findByIdAndPerson(3L, student)).thenReturn(Optional.of(finished));
        assertTrue(service.checkIn(student, 3L, Outcome.PARTLY, null).isEmpty());
    }

    @Test
    void listSplitsActiveAndFinishedGoalsAndCountsImprovements() {
        GrowthGoalEntity current = active(3L, TODAY);
        GrowthGoalEntity improvedGoal = finished(2L, 3.0, 4.0, null, null);
        GrowthGoalEntity flatGoal = finished(1L, 4.0, 4.0, 3.0, 3.0);
        when(repository.findByPersonOrderByCreatedAtDescIdDesc(student)).thenReturn(List.of(current, improvedGoal, flatGoal));

        Goals goals = service.list(student);

        assertEquals(3L, goals.active().id());
        assertTrue(goals.active().due());
        assertEquals(5, goals.active().now().self());
        assertEquals(2, goals.completed());
        assertEquals(1, goals.improved());
        assertSame(Boolean.FALSE, goals.finished().get(1).improved());
    }

    @Test
    void deleteOnlyRemovesTheCallersGoal() {
        GrowthGoalEntity own = active(1L, TODAY);
        when(repository.findByIdAndPerson(1L, student)).thenReturn(Optional.of(own));
        when(repository.findByIdAndPerson(2L, student)).thenReturn(Optional.empty());

        assertTrue(service.delete(student, 1L));
        assertFalse(service.delete(student, 2L));
        verify(repository).delete(own);
    }

    private static void assertInvalid(Runnable call, String message) {
        InvalidGoalException e = assertThrows(InvalidGoalException.class, call::run);
        assertEquals(message, e.getMessage());
    }

    private static SkillCheck check(Integer self, Double teacher, Double evidence) {
        return new SkillCheck("timeliness", self, teacher, null, evidence, null, null, null);
    }

    private static GrowthGoalEntity active(Long id, LocalDate checkIn) {
        GrowthGoalEntity goal = new GrowthGoalEntity();
        goal.setId(id);
        goal.setSkill("timeliness");
        goal.setAction("Plan ahead");
        goal.setCheckInDate(checkIn);
        goal.setCreatedAt(NOW.minusDays(14));
        goal.setStartSelf(5);
        goal.setStartTeacher(3.0);
        goal.setStartEvidence(3.7);
        return goal;
    }

    private static GrowthGoalEntity finished(Long id, Double startTeacher, Double endTeacher, Double startEvidence, Double endEvidence) {
        GrowthGoalEntity goal = active(id, TODAY.minusDays(20));
        goal.setStartTeacher(startTeacher);
        goal.setEndTeacher(endTeacher);
        goal.setStartEvidence(startEvidence);
        goal.setEndEvidence(endEvidence);
        goal.setOutcome(Outcome.PARTLY);
        goal.setCheckedInAt(NOW.minusDays(5));
        return goal;
    }
}
