package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Authentication, delegation and status mapping of the review and closure endpoints. */
class GradeReviewApiRestControllerTest {

    private final GradeReviewService reviewService = mock(GradeReviewService.class);
    private final SubmissionAiGrader aiGrader = mock(SubmissionAiGrader.class);
    private final AssignmentClosureService closureService = mock(AssignmentClosureService.class);
    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private final GradeReviewApiRestController reviews =
            new GradeReviewApiRestController(reviewService, aiGrader, personRepo, new ObjectMapper());
    private final AssignmentClosureApiRestController closures = new AssignmentClosureApiRestController(closureService, personRepo);

    private Person niko;
    private Person hop;

    @BeforeEach
    void setUp() {
        niko = teacher(3L, "niko");
        hop = student(5L, "hop");
        when(personRepo.findByUid("niko")).thenReturn(niko);
        when(personRepo.findByUid("hop")).thenReturn(hop);
        when(personRepo.findAllById(any())).thenReturn(List.of(niko));
    }

    private static UserDetails login(Person person) {
        return User.withUsername(person.getUid()).password("unused").authorities("ROLE_USER").build();
    }

    private static GradeReviewEntity review(GradeReviewEntity.Status status) {
        GradeReviewEntity review = new GradeReviewEntity();
        review.setId(55L);
        review.setSubmissionId(100L);
        review.setAssignmentId(10L);
        review.setReason(GradeReviewEntity.Reason.SIMILARITY_CHECK);
        review.setStatus(status);
        review.setOpenedById(3L);
        review.setOpenedAt(LocalDateTime.of(2026, 10, 26, 9, 0));
        review.setSimilarSubmissions("[{\"submissionId\":7,\"submitterName\":\"lex\",\"basis\":[\"SAME_LINK\"]}]");
        return review;
    }

    @Test
    void openingAReviewIsCreatedWithTheTeachersNameAndParsedEvidence() {
        when(reviewService.open(eq(100L), same(niko), eq(GradeReviewEntity.Reason.SIMILARITY_CHECK), eq("notes")))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.REVIEW_OPENED, review(GradeReviewEntity.Status.OPEN), null));

        ResponseEntity<?> response = reviews.open(100L,
                new GradeReviewApiRestController.OpenReviewRequest(GradeReviewEntity.Reason.SIMILARITY_CHECK, "notes"), login(niko));

        assertEquals(201, response.getStatusCode().value());
        var body = assertInstanceOf(GradeReviewApiRestController.ReviewResponse.class, response.getBody());
        assertEquals("niko", body.review().openedBy());
        assertEquals(7, body.review().similarSubmissions().get(0).get("submissionId"));
    }

    @Test
    void refusalsMapToTheirStatuses() {
        when(reviewService.decide(eq(55L), any(), any(), any(), any(), any()))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.RATIONALE_REQUIRED),
                        GradeReviewService.ReviewResult.of(GradingOutcome.REVIEW_NOT_OPEN),
                        GradeReviewService.ReviewResult.of(GradingOutcome.NOT_PERMITTED));
        var request = new GradeReviewApiRestController.DecisionRequest(GradeReviewEntity.Decision.NO_CHANGE, "", null, null);

        assertEquals(400, reviews.decide(55L, request, login(niko)).getStatusCode().value());
        assertEquals(409, reviews.decide(55L, request, login(niko)).getStatusCode().value());
        ResponseEntity<?> denied = reviews.decide(55L, request, login(hop));
        assertEquals(403, denied.getStatusCode().value());
        assertNull(((GradeReviewApiRestController.ReviewResponse) denied.getBody()).review(), "a denied caller learns nothing");
    }

    @Test
    void aStudentCannotListReviews() {
        when(reviewService.reviewsOf(100L, hop)).thenReturn(Optional.empty());
        when(reviewService.reviewsOf(100L, niko)).thenReturn(Optional.of(List.of(review(GradeReviewEntity.Status.DECIDED))));

        assertEquals(403, reviews.reviewsOf(100L, login(hop)).getStatusCode().value());
        ResponseEntity<?> listed = reviews.reviewsOf(100L, login(niko));
        assertEquals(200, listed.getStatusCode().value());
        assertInstanceOf(List.class, listed.getBody());
    }

    @Test
    void theAiReEvaluationGoesThroughTheAiGrader() {
        when(aiGrader.evaluateForReview(55L, niko))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.EVIDENCE_RECORDED, review(GradeReviewEntity.Status.OPEN), null));

        assertEquals(200, reviews.aiEvaluation(55L, login(niko)).getStatusCode().value());
    }

    @Test
    void closingReturnsTheSummaryAndRefusesAnonymousCallers() {
        var summary = new AssignmentClosureService.Summary(10L, LocalDateTime.of(2026, 10, 26, 15, 0), 3, 1, 3);
        when(closureService.close(10L, niko))
                .thenReturn(new AssignmentClosureService.Result(GradingOutcome.ASSIGNMENT_FINALIZED, summary));
        when(closureService.close(10L, hop)).thenReturn(new AssignmentClosureService.Result(GradingOutcome.NOT_PERMITTED, null));

        ResponseEntity<?> closed = closures.close(10L, login(niko));
        assertEquals(200, closed.getStatusCode().value());
        assertEquals(summary, ((AssignmentClosureApiRestController.ClosureResponse) closed.getBody()).summary());
        assertEquals(403, closures.close(10L, login(hop)).getStatusCode().value());
        assertEquals(401, closures.close(10L, null).getStatusCode().value());
        assertEquals(401, reviews.open(100L, new GradeReviewApiRestController.OpenReviewRequest(null, null), null)
                .getStatusCode().value());
    }

    private static GradeReviewEntity petition() {
        GradeReviewEntity petition = review(GradeReviewEntity.Status.OPEN);
        petition.setReason(GradeReviewEntity.Reason.PETITION);
        petition.setPetitionMessage("v2 adds part 2");
        petition.setOpenedById(5L);
        petition.setEvidence("Teacher notes");
        petition.setAiGrade(0.4);
        return petition;
    }

    @Test
    void aStudentsPetitionIsCreatedAndShowsOnlyWhatTheStudentMaySee() {
        when(reviewService.petition(100L, hop, "v2 adds part 2"))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.REVIEW_OPENED, petition(), null));

        ResponseEntity<?> response = reviews.petition(100L,
                new GradeReviewApiRestController.PetitionRequest("v2 adds part 2"), login(hop));

        assertEquals(201, response.getStatusCode().value());
        var body = assertInstanceOf(GradeReviewApiRestController.PetitionResponse.class, response.getBody());
        assertEquals(GradingOutcome.REVIEW_OPENED, body.outcome());
        assertEquals("v2 adds part 2", body.petition().message());
        assertEquals(GradeReviewEntity.Status.OPEN, body.petition().status());
    }

    @Test
    void aRefusedPetitionMapsToItsStatusWithoutAPetition() {
        when(reviewService.petition(eq(100L), same(hop), any()))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.NO_MANUAL_OVERRIDE),
                        GradeReviewService.ReviewResult.of(GradingOutcome.REASON_REQUIRED),
                        GradeReviewService.ReviewResult.of(GradingOutcome.NOT_PERMITTED));
        var request = new GradeReviewApiRestController.PetitionRequest("x");

        assertEquals(409, reviews.petition(100L, request, login(hop)).getStatusCode().value());
        assertEquals(400, reviews.petition(100L, request, login(hop)).getStatusCode().value());
        ResponseEntity<?> denied = reviews.petition(100L, request, login(hop));
        assertEquals(403, denied.getStatusCode().value());
        assertNull(((GradeReviewApiRestController.PetitionResponse) denied.getBody()).petition());
    }

    @Test
    void aStudentListsTheirPetitionsWithoutTheTeachersEvidence() {
        when(reviewService.petitionsOf(100L, hop)).thenReturn(Optional.of(List.of(petition())));
        when(reviewService.petitionsOf(100L, niko)).thenReturn(Optional.empty());

        ResponseEntity<?> listed = reviews.petitionsOf(100L, login(hop));

        assertEquals(200, listed.getStatusCode().value());
        var petitions = (List<?>) listed.getBody();
        var view = assertInstanceOf(GradeReviewApiRestController.PetitionView.class, petitions.get(0));
        assertEquals("v2 adds part 2", view.message());
        assertTrue(java.util.Arrays.stream(GradeReviewApiRestController.PetitionView.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("evidence") || component.getName().equals("aiGrade")));
        assertEquals(403, reviews.petitionsOf(100L, login(niko)).getStatusCode().value());
    }

    @Test
    void teachersSeeOpenReviewsOnAnAssignmentWithThePetitionText() {
        when(reviewService.openReviewsOf(10L, niko)).thenReturn(Optional.of(List.of(petition())));
        when(reviewService.openReviewsOf(10L, hop)).thenReturn(Optional.empty());

        ResponseEntity<?> listed = reviews.openReviewsOf(10L, login(niko));

        assertEquals(200, listed.getStatusCode().value());
        var view = (GradeReviewApiRestController.ReviewView) ((List<?>) listed.getBody()).get(0);
        assertEquals("v2 adds part 2", view.petitionMessage());
        assertEquals(GradeReviewEntity.Reason.PETITION, view.reason());
        assertEquals(403, reviews.openReviewsOf(10L, login(hop)).getStatusCode().value());
    }

    @Test
    void anonymousCallersNeverReachTheServices() {
        reviews.decide(55L, new GradeReviewApiRestController.DecisionRequest(null, null, null, null), null);
        reviews.similarity(55L, null);
        reviews.recordEvidence(55L, new GradeReviewApiRestController.EvidenceRequest("x"), null);
        assertEquals(401, reviews.petition(100L, new GradeReviewApiRestController.PetitionRequest("x"), null)
                .getStatusCode().value());
        assertEquals(401, reviews.petitionsOf(100L, null).getStatusCode().value());
        assertEquals(401, reviews.openReviewsOf(10L, null).getStatusCode().value());

        verifyNoInteractions(reviewService);
    }
}
