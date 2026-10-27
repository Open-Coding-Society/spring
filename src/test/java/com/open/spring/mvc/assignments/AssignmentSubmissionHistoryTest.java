package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.synergy.GradebookService;

/**
 * Resubmitting replaces the submission in place and keeps the earlier version in history; rows
 * from before that (a resubmit used to add a row) are left alone and read as earlier versions.
 */
class AssignmentSubmissionHistoryTest {

    private AssignmentSubmissionJPA submissionRepo;
    private AssignmentSubmissionHistoryJPA historyRepo;
    private AssignmentAiGradingService aiGradingService;
    private AssignmentSubmissionHistoryService service;
    private Person alice;
    private Assignment assignment;

    @BeforeEach
    void setUp() {
        submissionRepo = mock(AssignmentSubmissionJPA.class);
        historyRepo = mock(AssignmentSubmissionHistoryJPA.class);
        aiGradingService = mock(AssignmentAiGradingService.class);
        service = historyService(submissionRepo, historyRepo, aiGradingService);
        alice = student(1L, "alice");
        assignment = assignment(10L, "Loops");
        when(submissionRepo.save(any(AssignmentSubmission.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** A real history service over mocked repositories; its transactions just run the callback. */
    static AssignmentSubmissionHistoryService historyService(AssignmentSubmissionJPA submissionRepo,
            AssignmentSubmissionHistoryJPA historyRepo, AssignmentAiGradingService aiGradingService) {
        return new AssignmentSubmissionHistoryService(submissionRepo, historyRepo, aiGradingService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), mock(JdbcTemplate.class), null);
    }

    private AssignmentSubmission draft(String url) {
        return new AssignmentSubmission(assignment, alice, Map.of("type", "link", "url", url), "comment", false);
    }

    private AssignmentSubmission stored(Long id, String url, Double grade) {
        AssignmentSubmission submission = submission(id, assignment);
        submission.setSubmitter(alice);
        submission.setContent(Map.of("type", "link", "url", url));
        submission.setGrade(grade);
        submission.setFeedback(grade == null ? null : "feedback for " + url);
        return submission;
    }

    @Nested
    class Submit {
        @Test
        void firstSubmissionIsInsertedWithoutHistory() {
            AssignmentSubmission draft = draft("https://a");

            AssignmentSubmissionHistoryService.SubmitResult result = service.submit(draft);

            assertFalse(result.replaced());
            assertSame(draft, result.submission());
            assertNotNull(draft.getSubmittedAt());
            verify(historyRepo, never()).save(any());
        }

        @Test
        void resubmitKeepsTheRowAndMovesTheGradedVersionToHistory() {
            AssignmentSubmission current = stored(5L, "https://old", 0.9);
            current.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
            current.setNeedsAiRegrade(true);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current));

            AssignmentSubmissionHistoryService.SubmitResult result = service.submit(draft("https://new"));

            assertTrue(result.replaced());
            assertSame(current, result.submission());
            assertEquals(5L, current.getId());
            assertEquals("https://new", current.getContent().get("url"));
            assertNull(current.getGrade());
            assertNull(current.getFeedback());
            assertNull(current.getNeedsAiRegrade());
            assertTrue(current.getSubmittedAt().isAfter(LocalDateTime.of(2026, 10, 1, 9, 0)));

            ArgumentCaptor<AssignmentSubmissionHistory> saved = ArgumentCaptor.forClass(AssignmentSubmissionHistory.class);
            verify(historyRepo).save(saved.capture());
            AssignmentSubmissionHistory earlier = saved.getValue();
            assertSame(current, earlier.getSubmission());
            assertEquals("https://old", earlier.getContent().get("url"));
            assertEquals(0.9, earlier.getGrade());
            assertEquals("feedback for https://old", earlier.getFeedback());
            assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), earlier.getSubmittedAt());
            assertNotNull(earlier.getReplacedAt());
        }

        @Test
        void resubmitOverOldDuplicatesOnlyReplacesTheNewestAndLeavesOlderRowsAlone() {
            AssignmentSubmission newest = stored(7L, "https://second", 0.5);
            AssignmentSubmission oldest = stored(3L, "https://first", 0.8);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(newest, oldest));

            service.submit(draft("https://third"));

            assertEquals("https://third", newest.getContent().get("url"));
            assertEquals("https://first", oldest.getContent().get("url"));
            assertEquals(0.8, oldest.getGrade());
            verify(submissionRepo, never()).save(oldest);
        }
    }

    @Nested
    class AutoGrade {
        @Test
        void appliesTheGradeWhenTheWorkIsStillCurrent() throws Exception {
            AssignmentSubmission submitted = service.submit(draft("https://a")).submission();
            submitted.setId(5L);
            AssignmentSubmission reloaded = stored(5L, "https://a", null);
            reloaded.setSubmittedAt(submitted.getSubmittedAt());
            when(submissionRepo.findById(5L)).thenReturn(Optional.of(reloaded));
            when(aiGradingService.grade(submitted))
                    .thenReturn(AssignmentAiGradingService.GradeResult.graded(0.9, 4, "Good", false));

            service.autoGrade(submitted);

            assertEquals(0.9, submitted.getGrade());
            assertEquals(0.9, reloaded.getGrade());
            verify(submissionRepo).save(reloaded);
        }

        @Test
        void dropsAStaleGradeWhenTheStudentResubmittedWhileGrading() throws Exception {
            AssignmentSubmission first = stored(5L, "https://first", null);
            first.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0, 0));
            AssignmentSubmission afterResubmit = stored(5L, "https://second", null);
            afterResubmit.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0, 20));
            when(submissionRepo.findById(5L)).thenReturn(Optional.of(afterResubmit));
            when(aiGradingService.grade(first))
                    .thenReturn(AssignmentAiGradingService.GradeResult.graded(0.2, 2, "Old work", false));

            service.autoGrade(first);

            assertNull(afterResubmit.getGrade());
            verify(submissionRepo, never()).save(any());
        }

        @Test
        void aFailedGradeOfStaleWorkDoesNotQueueTheNewWork() throws Exception {
            AssignmentSubmission first = stored(5L, "https://first", null);
            first.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0, 0));
            AssignmentSubmission afterResubmit = stored(5L, "https://second", null);
            afterResubmit.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0, 20));
            when(submissionRepo.findById(5L)).thenReturn(Optional.of(afterResubmit));
            when(aiGradingService.grade(first)).thenReturn(AssignmentAiGradingService.GradeResult.failed("timeout"));

            service.autoGrade(first);

            assertNull(afterResubmit.getNeedsAiRegrade());
            verify(submissionRepo, never()).save(any());
        }
    }

    @Nested
    class Versions {
        @Test
        void latestOnlyKeepsTheNewestRowPerAssignmentAndSubmitter() {
            Assignment other = assignment(11L, "Arrays");
            AssignmentSubmission older = stored(1L, "https://1", 0.5);
            AssignmentSubmission newer = stored(2L, "https://2", null);
            AssignmentSubmission otherAssignment = submission(3L, other);
            otherAssignment.setSubmitter(alice);

            List<AssignmentSubmission> latest =
                    AssignmentSubmissionHistoryService.latestOnly(List.of(older, newer, otherAssignment));

            assertEquals(List.of(newer, otherAssignment), latest);
        }

        @Test
        void gradebookCardShowsOnlyTheCurrentRowAndCountsOldDuplicatesWithSavedHistory() {
            AssignmentSubmission older = stored(1L, "https://1", 0.5);
            AssignmentSubmission newer = stored(2L, "https://2", null);
            GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
            AssignmentAiRegradeService queue = mock(AssignmentAiRegradeService.class);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(older, newer));
            when(queue.queueSnapshot()).thenReturn(new AssignmentAiRegradeService.QueueSnapshot(false, 0, 15, List.of()));
            when(jdbc.queryForList(startsWith("SELECT submission_id")))
                    .thenReturn(List.of(Map.of("submission_id", 2L, "n", 2L)));
            GradebookService gradebook = new GradebookService(groups, submissionRepo, queue, new CourseGroupProperties(),
                    jdbc, mock(PersonJpaRepository.class));

            List<GradebookService.SubmissionEntry> entries = gradebook.card(alice).submissions();

            assertEquals(1, entries.size());
            assertEquals(2L, entries.get(0).submissionId());
            assertEquals(3, entries.get(0).earlierVersions()); // 1 old duplicate row + 2 saved versions
        }

        @Test
        void historyListsCurrentThenSavedVersionsThenOldDuplicates() {
            AssignmentSubmission legacy = stored(3L, "https://legacy", 0.4);
            AssignmentSubmission current = stored(7L, "https://now", null);
            AssignmentSubmissionHistory saved = AssignmentSubmissionHistory.snapshotOf(stored(7L, "https://before", 0.6),
                    LocalDateTime.of(2026, 10, 20, 8, 0));
            saved.setId(40L);
            when(historyRepo.findBySubmissionIdOrderByIdDesc(7L)).thenReturn(List.of(saved));
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current, legacy));

            assertSame(current, service.currentOf(legacy));
            AssignmentSubmissionHistoryService.SubmissionHistory history = service.historyOf(current);

            assertEquals(7L, history.submissionId());
            assertEquals("Loops", history.assignmentName());
            assertEquals(3, history.versions().size());
            assertTrue(history.versions().get(0).current());
            assertEquals("https://now", history.versions().get(0).content().get("url"));
            assertEquals(40L, history.versions().get(1).historyId());
            assertEquals(0.6, history.versions().get(1).grade());
            assertEquals(3L, history.versions().get(2).submissionId());
            assertEquals(0.4, history.versions().get(2).grade());
        }
    }

    @Nested
    class Api {
        private AssignmentSubmissionAPIController controller;
        private PersonJpaRepository people;
        private AssignmentJpaRepository assignments;
        private GroupsJpaRepository groups;

        @BeforeEach
        void setUpController() throws Exception {
            controller = new AssignmentSubmissionAPIController();
            people = mock(PersonJpaRepository.class);
            assignments = mock(AssignmentJpaRepository.class);
            ReflectionTestUtils.setField(controller, "personRepo", people);
            ReflectionTestUtils.setField(controller, "assignmentRepo", assignments);
            ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
            ReflectionTestUtils.setField(controller, "aiGradingService", aiGradingService);
            ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
            ReflectionTestUtils.setField(controller, "historyService", service);
            groups = mock(GroupsJpaRepository.class);
            ReflectionTestUtils.setField(controller, "groupRepo", groups);
            when(people.findByUid("alice")).thenReturn(alice);
            when(people.findById(1L)).thenReturn(Optional.of(alice));
            assignment.setAssignmentType("link");
            when(assignments.findById(10L)).thenReturn(Optional.of(assignment));
            when(aiGradingService.grade(any())).thenReturn(AssignmentAiGradingService.GradeResult.notGradeable("link"));
        }

        private UserDetails login(String uid) {
            return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
        }

        private AssignmentSubmissionAPIController.SubmissionRequestDto request(String url) {
            var request = new AssignmentSubmissionAPIController.SubmissionRequestDto();
            request.submitterId = 1L;
            request.content = Map.of("type", "link", "url", url);
            return request;
        }

        @Test
        void resubmitReturnsOkWithTheSameSubmissionId() {
            when(submissionRepo.findBySubmitterId(1L))
                    .thenReturn(List.of(stored(5L, "https://old", 0.9)));

            var response = controller.submitAssignment(10L, request("https://new"), login("alice"));

            assertEquals(200, response.getStatusCode().value());
            assertEquals(5L, ((AssignmentSubmissionReturnDto) response.getBody()).id);
        }

        @Test
        void firstSubmitStillReturnsCreated() {
            var response = controller.submitAssignment(10L, request("https://new"), login("alice"));

            assertEquals(201, response.getStatusCode().value());
        }

        @Test
        void studentCanSubmitForTheirGroupButNotAnotherGroup() {
            Groups team = new Groups();
            ReflectionTestUtils.setField(team, "id", 50L);
            when(groups.findById(50L)).thenReturn(Optional.of(team));
            when(groups.findGroupsByPersonId(1L)).thenReturn(List.of(team));
            when(people.findByUid("bob")).thenReturn(student(2L, "bob"));
            var request = request("https://group");
            request.submitterId = 50L;
            request.isGroupSubmission = true;

            assertEquals(201, controller.submitAssignment(10L, request, login("alice")).getStatusCode().value());
            assertEquals(403, controller.submitAssignment(10L, request, login("bob")).getStatusCode().value());
        }

        @Test
        void historyIsVisibleToTheSubmitterButNotAnotherStudent() {
            AssignmentSubmission current = stored(5L, "https://now", null);
            when(submissionRepo.findById(5L)).thenReturn(Optional.of(current));
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current));
            when(people.findByUid("bob")).thenReturn(student(2L, "bob"));

            assertEquals(200, controller.getSubmissionHistory(5L, login("alice")).getStatusCode().value());
            assertEquals(403, controller.getSubmissionHistory(5L, login("bob")).getStatusCode().value());
            assertEquals(404, controller.getSubmissionHistory(6L, login("alice")).getStatusCode().value());
        }
    }
}
