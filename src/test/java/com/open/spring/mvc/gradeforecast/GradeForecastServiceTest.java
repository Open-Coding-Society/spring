package com.open.spring.mvc.gradeforecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.evaluationAdmin.AdminEvaluation;
import com.open.spring.mvc.evaluationAdmin.AdminEvaluationRepository;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService;
import com.open.spring.mvc.gradeforecast.GradeForecastService.Focus;
import com.open.spring.mvc.gradeforecast.GradeForecastService.Forecast;
import com.open.spring.mvc.gradeforecast.GradeForecastService.InvalidForecastException;
import com.open.spring.mvc.gradeforecast.GradeForecastService.Retrospective;
import com.open.spring.mvc.gradeforecast.GradeForecastService.SkillCheck;
import com.open.spring.mvc.person.Person;

class GradeForecastServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);

    @Mock
    private GradeForecastJpaRepository repository;

    @Mock
    private GradeCalculatorService calculatorService;

    @Mock
    private AdminEvaluationRepository teacherEvaluationRepository;

    @Mock
    private AssignmentSubmissionJPA submissionRepository;

    private GradeForecastService service;
    @Mock
    private Person student;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new GradeForecastService(repository, calculatorService, teacherEvaluationRepository, submissionRepository, clock);
        when(student.getId()).thenReturn(7L);
        when(repository.save(any(GradeForecastEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findFirstByPersonOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        when(calculatorService.currentGrade(student)).thenReturn(Optional.empty());
        when(submissionRepository.findBySubmitter(student)).thenReturn(List.of());
        when(teacherEvaluationRepository.findByUserId(7)).thenReturn(Optional.empty());
    }

    @Test
    void recordSavesTheForecastWithTheCalculatorGradeAtThatMoment() {
        when(calculatorService.currentGrade(student)).thenReturn(Optional.of(88.3));

        GradeForecastEntity saved = service.record(student, new Forecast(ratings(4), 87.44, 92.0));

        assertSame(student, saved.getPerson());
        assertEquals(4, saved.getTimeliness());
        assertEquals(87.4, saved.getModelGrade());
        assertEquals(92.0, saved.getStudentGuess());
        assertEquals(88.3, saved.getCurrentGrade());
        assertEquals(NOW, saved.getCreatedAt());
    }

    @Test
    void repeatingTheSameRatingsSoonEditsTheLastForecast() {
        GradeForecastEntity last = forecast(4, 80.0, NOW.minusMinutes(5));
        when(repository.findFirstByPersonOrderByCreatedAtDescIdDesc(student)).thenReturn(Optional.of(last));

        GradeForecastEntity saved = service.record(student, new Forecast(ratings(4), 82.0, null));

        assertSame(last, saved);
        assertEquals(82.0, saved.getModelGrade());
    }

    @Test
    void changedRatingsOrAnOldForecastStartANewOne() {
        GradeForecastEntity recentButDifferent = forecast(3, 80.0, NOW.minusMinutes(5));
        when(repository.findFirstByPersonOrderByCreatedAtDescIdDesc(student)).thenReturn(Optional.of(recentButDifferent));
        assertNotSame(recentButDifferent, service.record(student, new Forecast(ratings(4), 82.0, null)));

        GradeForecastEntity sameButOld = forecast(4, 80.0, NOW.minusHours(2));
        when(repository.findFirstByPersonOrderByCreatedAtDescIdDesc(student)).thenReturn(Optional.of(sameButOld));
        assertNotSame(sameButOld, service.record(student, new Forecast(ratings(4), 82.0, null)));
    }

    @Test
    void recordRejectsInvalidForecasts() {
        Map<String, Integer> missing = ratings(4);
        missing.remove("integrity");
        assertRejected(new Forecast(missing, 80.0, null), "integrity is required");
        Map<String, Integer> outOfRange = ratings(4);
        outOfRange.put("behavior", 0);
        assertRejected(new Forecast(outOfRange, 80.0, null), "behavior must be from 1 to 5");
        Map<String, Integer> unknown = ratings(4);
        unknown.put("charisma", 3);
        assertRejected(new Forecast(unknown, 80.0, null), "unknown skill charisma");
        assertRejected(new Forecast(ratings(4), null, null), "modelGrade is required");
        assertRejected(new Forecast(ratings(4), 101.0, null), "modelGrade must be from 0 to 100");
        assertRejected(new Forecast(ratings(4), 80.0, -1.0), "studentGuess must be from 0 to 100");
        assertRejected(new Forecast(null, 80.0, null), "ratings are required");
        verify(repository, never()).save(any());
    }

    @Test
    void deleteOnlyRemovesTheCallersOwnForecast() {
        GradeForecastEntity own = forecast(4, 80.0, NOW);
        when(repository.findByIdAndPerson(1L, student)).thenReturn(Optional.of(own));
        when(repository.findByIdAndPerson(2L, student)).thenReturn(Optional.empty());

        assertTrue(service.delete(student, 1L));
        assertFalse(service.delete(student, 2L));
        verify(repository).delete(own);
    }

    @Test
    void retrospectiveWithNoForecastsIsEmptyButStillListsEverySkill() {
        when(repository.findByPersonOrderByCreatedAtAscIdAsc(student)).thenReturn(List.of());

        Retrospective retro = service.retrospective(student);

        assertTrue(retro.forecasts().isEmpty());
        assertEquals(0, retro.model().forecasts());
        assertNull(retro.model().bias());
        assertEquals(11, retro.skills().size());
        assertNull(retro.lastChange());
        assertNull(retro.focus());
    }

    @Test
    void retrospectiveMeasuresAccuracyAgainstTheCurrentCalculatorGrade() {
        GradeForecastEntity first = forecast(3, 80.0, NOW.minusDays(20));
        first.setStudentGuess(95.0);
        GradeForecastEntity second = forecast(3, 90.0, NOW.minusDays(5));
        second.setStudentGuess(93.0);
        when(repository.findByPersonOrderByCreatedAtAscIdAsc(student)).thenReturn(List.of(first, second));
        when(calculatorService.currentGrade(student)).thenReturn(Optional.of(84.0));

        Retrospective retro = service.retrospective(student);

        assertEquals(84.0, retro.currentGrade());
        assertEquals(2, retro.model().forecasts());
        assertEquals(1.0, retro.model().bias());
        assertEquals(5.0, retro.model().meanError());
        assertEquals(10.0, retro.guess().bias());
        assertEquals(10.0, retro.guess().meanError());
    }

    @Test
    void calibrationShowsWhetherGuessesGotCloserToTheGradeAtTheTime() {
        GradeForecastEntity first = forecast(3, 80.0, NOW.minusDays(20));
        first.setStudentGuess(95.0);
        first.setCurrentGrade(82.0);
        GradeForecastEntity noGuess = forecast(3, 81.0, NOW.minusDays(10));
        noGuess.setCurrentGrade(84.0);
        GradeForecastEntity latest = forecast(3, 84.0, NOW.minusDays(1));
        latest.setStudentGuess(88.0);
        latest.setCurrentGrade(86.0);
        when(repository.findByPersonOrderByCreatedAtAscIdAsc(student)).thenReturn(List.of(first, noGuess, latest));

        GradeForecastService.Calibration calibration = service.retrospective(student).calibration();

        assertEquals(List.of(13.0, 2.0), calibration.gaps());
        assertEquals(13.0, calibration.first());
        assertEquals(2.0, calibration.latest());
        assertEquals(-11.0, calibration.change());
    }

    @Test
    void retrospectiveComparesSelfRatingsWithTheTeacherAndEvidence() {
        GradeForecastEntity first = forecast(3, 80.0, NOW.minusDays(20));
        GradeForecastEntity latest = forecast(4, 86.0, NOW.minusDays(1));
        latest.setTimeliness(5);
        latest.setTechTalk(2);
        when(repository.findByPersonOrderByCreatedAtAscIdAsc(student)).thenReturn(List.of(first, latest));
        AdminEvaluation teacher = teacherRatings(4.0);
        teacher.setTechTalk(4.0);
        when(teacherEvaluationRepository.findByUserId(7)).thenReturn(Optional.of(teacher));
        when(submissionRepository.findBySubmitter(student)).thenReturn(List.of(
                submission(1L, 10L, false), submission(2L, 11L, true), submission(3L, 11L, false), submission(4L, 12L, true)));

        Retrospective retro = service.retrospective(student);

        SkillCheck timeliness = skill(retro, "timeliness");
        assertEquals(5, timeliness.self());
        assertEquals(1.0, timeliness.teacherGap());
        assertEquals(3.7, timeliness.evidence());
        assertEquals("2 of 3 submissions on time", timeliness.evidenceDetail());
        assertEquals(1.3, timeliness.evidenceGap());
        assertEquals(2, timeliness.changeSinceFirst());
        assertEquals(-2.0, skill(retro, "techTalk").teacherGap());
        assertNull(skill(retro, "attendance").evidence());

        assertEquals(6.0, retro.lastChange().modelGradeChange());
        assertEquals(Map.ofEntries(Map.entry("attendance", 1), Map.entry("workHabits", 1), Map.entry("behavior", 1),
                Map.entry("timeliness", 2), Map.entry("techSense", 1), Map.entry("techTalk", -1), Map.entry("techGrowth", 1),
                Map.entry("advocacy", 1), Map.entry("communication", 1), Map.entry("integrity", 1),
                Map.entry("organization", 1)), retro.lastChange().ratingChanges());

        assertEquals("timeliness", retro.focus().skill());
        assertEquals("You rate yourself 5, but your teacher rates you 4. Ask what would earn the higher score.",
                retro.focus().reason());
    }

    @Test
    void focusFallsBackToEvidenceThenToTheLowestRating() {
        SkillCheck overEvidence = new SkillCheck("timeliness", 5, null, null, 3.0, "2 of 4 submissions on time", 2.0, null);
        SkillCheck low = new SkillCheck("techTalk", 2, 2.5, -0.5, null, null, null, null);

        Focus evidenceFocus = GradeForecastService.focus(List.of(low, overEvidence));
        assertEquals("timeliness", evidenceFocus.skill());
        assertEquals("You rate yourself 5, but your records point to 3 (2 of 4 submissions on time).", evidenceFocus.reason());

        Focus lowestFocus = GradeForecastService.focus(List.of(low,
                new SkillCheck("behavior", 4, null, null, null, null, null, null)));
        assertEquals("techTalk", lowestFocus.skill());
        assertEquals("Your views agree, so the biggest lift is your lowest rating (2).", lowestFocus.reason());
    }

    private void assertRejected(Forecast forecast, String message) {
        InvalidForecastException e = assertThrows(InvalidForecastException.class, () -> service.record(student, forecast));
        assertEquals(message, e.getMessage());
    }

    private static SkillCheck skill(Retrospective retro, String name) {
        return retro.skills().stream().filter(s -> s.skill().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Integer> ratings(int rating) {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        GradeForecastService.SKILLS.keySet().forEach(name -> ratings.put(name, rating));
        return new HashMap<>(ratings);
    }

    /** A forecast with every skill rated the same. */
    private static GradeForecastEntity forecast(int rating, double modelGrade, LocalDateTime createdAt) {
        GradeForecastEntity forecast = new GradeForecastEntity();
        forecast.setAttendance(rating);
        forecast.setWorkHabits(rating);
        forecast.setBehavior(rating);
        forecast.setTimeliness(rating);
        forecast.setTechSense(rating);
        forecast.setTechTalk(rating);
        forecast.setTechGrowth(rating);
        forecast.setAdvocacy(rating);
        forecast.setCommunication(rating);
        forecast.setIntegrity(rating);
        forecast.setOrganization(rating);
        forecast.setModelGrade(modelGrade);
        forecast.setCreatedAt(createdAt);
        return forecast;
    }

    private static AdminEvaluation teacherRatings(double rating) {
        AdminEvaluation teacher = new AdminEvaluation();
        teacher.setAttendance(rating);
        teacher.setWorkHabits(rating);
        teacher.setBehavior(rating);
        teacher.setTimeliness(rating);
        teacher.setTechSense(rating);
        teacher.setTechTalk(rating);
        teacher.setTechGrowth(rating);
        teacher.setAdvocacy(rating);
        teacher.setCommunication(rating);
        teacher.setIntegrity(rating);
        teacher.setOrganization(rating);
        return teacher;
    }

    /** A submission; for each assignment only the highest id counts. */
    private static AssignmentSubmission submission(Long id, Long assignmentId, boolean late) {
        Assignment assignment = new Assignment();
        assignment.setId(assignmentId);
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setId(id);
        submission.setAssignment(assignment);
        submission.setIsLate(late);
        return submission;
    }
}
