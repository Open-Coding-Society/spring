package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Grade review endpoints (see {@link GradeReviewService}). Teachers open, gather evidence for and
 * decide reviews; a student files and follows petitions on their own submission.
 */
@RestController
@RequestMapping("/api/grade-reviews")
public class GradeReviewApiRestController {

    public record OpenReviewRequest(GradeReviewEntity.Reason reason, String evidence) {
    }

    public record PetitionRequest(String message) {
    }

    public record EvidenceRequest(String evidence) {
    }

    public record DecisionRequest(GradeReviewEntity.Decision decision, String rationale, Double grade, String feedback) {
    }

    /** A review as teachers see it, with names resolved and similarity evidence parsed. */
    public record ReviewView(Long id, Long submissionId, Long assignmentId, GradeReviewEntity.Reason reason,
            GradeReviewEntity.Status status, Double gradeAtOpen, String petitionMessage, String evidence,
            Double aiGrade, String aiFeedback, List<Map<String, Object>> similarSubmissions,
            GradeReviewEntity.Decision decision, String rationale, Double adjustedGrade, String adjustedFeedback,
            String openedBy, LocalDateTime openedAt, String decidedBy, LocalDateTime decidedAt) {
    }

    /** A petition as its student sees it: the decision, without the teacher's evidence. */
    public record PetitionView(Long id, GradeReviewEntity.Status status, String message, Double gradeAtOpen,
            GradeReviewEntity.Decision decision, String rationale, Double adjustedGrade, String adjustedFeedback,
            LocalDateTime openedAt, LocalDateTime decidedAt) {
        static PetitionView of(GradeReviewEntity review) {
            return new PetitionView(review.getId(), review.getStatus(), review.getPetitionMessage(),
                    review.getGradeAtOpen(), review.getDecision(), review.getRationale(), review.getAdjustedGrade(),
                    review.getAdjustedFeedback(), review.getOpenedAt(), review.getDecidedAt());
        }
    }

    public record ReviewResponse(GradingOutcome outcome, ReviewView review, SubmissionGradingState grading,
            String detail) {
    }

    public record PetitionResponse(GradingOutcome outcome, PetitionView petition, SubmissionGradingState grading) {
    }

    private final GradeReviewService reviewService;
    private final SubmissionAiGrader aiGrader;
    private final PersonJpaRepository personRepo;
    private final ObjectMapper objectMapper;

    public GradeReviewApiRestController(GradeReviewService reviewService, SubmissionAiGrader aiGrader,
            PersonJpaRepository personRepo, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.aiGrader = aiGrader;
        this.personRepo = personRepo;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<?> reviewsOf(@PathVariable Long submissionId, @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher -> list(reviewService.reviewsOf(submissionId, teacher)));
    }

    /** Open reviews on the assignment's submissions, so the tracker can flag petitions before close. */
    @GetMapping("/assignment/{assignmentId}/open")
    public ResponseEntity<?> openReviewsOf(@PathVariable Long assignmentId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher -> list(reviewService.openReviewsOf(assignmentId, teacher)));
    }

    @PostMapping("/submission/{submissionId}")
    public ResponseEntity<?> open(@PathVariable Long submissionId, @RequestBody OpenReviewRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher ->
                respond(reviewService.open(submissionId, teacher, request.reason(), request.evidence())));
    }

    @PostMapping("/submission/{submissionId}/petition")
    public ResponseEntity<?> petition(@PathVariable Long submissionId, @RequestBody PetitionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, student -> {
            GradeReviewService.ReviewResult result = reviewService.petition(submissionId, student, request.message());
            PetitionView petition = result.review() == null ? null : PetitionView.of(result.review());
            return ResponseEntity.status(GradingResponse.statusOf(result.outcome()))
                    .body(new PetitionResponse(result.outcome(), petition, result.grading()));
        });
    }

    @GetMapping("/submission/{submissionId}/petitions")
    public ResponseEntity<?> petitionsOf(@PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, student -> reviewService.petitionsOf(submissionId, student)
                .<ResponseEntity<?>>map(petitions -> ResponseEntity.ok(petitions.stream().map(PetitionView::of).toList()))
                .orElseGet(this::notPermitted));
    }

    @PutMapping("/{reviewId}/evidence")
    public ResponseEntity<?> recordEvidence(@PathVariable Long reviewId, @RequestBody EvidenceRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher ->
                respond(reviewService.recordEvidence(reviewId, teacher, request.evidence())));
    }

    /** Adds an AI re-evaluation to the review's evidence. */
    @PostMapping("/{reviewId}/ai-evaluation")
    public ResponseEntity<?> aiEvaluation(@PathVariable Long reviewId, @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher -> respond(aiGrader.evaluateForReview(reviewId, teacher)));
    }

    /** Adds submissions with identical content to the review's evidence. */
    @PostMapping("/{reviewId}/similarity")
    public ResponseEntity<?> similarity(@PathVariable Long reviewId, @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher -> respond(reviewService.collectSimilarity(reviewId, teacher)));
    }

    @PostMapping("/{reviewId}/decision")
    public ResponseEntity<?> decide(@PathVariable Long reviewId, @RequestBody DecisionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return signedIn(userDetails, teacher -> respond(reviewService.decide(reviewId, teacher,
                request.decision(), request.rationale(), request.grade(), request.feedback())));
    }

    private ResponseEntity<?> signedIn(UserDetails userDetails, Function<Person, ResponseEntity<?>> action) {
        return GradingResponse.signedIn(personRepo, userDetails, action);
    }

    private ResponseEntity<?> list(Optional<List<GradeReviewEntity>> reviews) {
        return reviews.<ResponseEntity<?>>map(found -> ResponseEntity.ok(toViews(found))).orElseGet(this::notPermitted);
    }

    private ResponseEntity<?> notPermitted() {
        return respond(GradeReviewService.ReviewResult.of(GradingOutcome.NOT_PERMITTED));
    }

    private ResponseEntity<ReviewResponse> respond(GradeReviewService.ReviewResult result) {
        ReviewView review = result.review() == null ? null : toViews(List.of(result.review())).get(0);
        return ResponseEntity.status(GradingResponse.statusOf(result.outcome()))
                .body(new ReviewResponse(result.outcome(), review, result.grading(), result.detail()));
    }

    private List<ReviewView> toViews(List<GradeReviewEntity> reviews) {
        Set<Long> personIds = reviews.stream()
                .flatMap(review -> Stream.of(review.getOpenedById(), review.getDecidedById()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = personRepo.findAllById(personIds).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName, (first, second) -> first));
        Function<Long, String> name = id -> id == null ? null : names.get(id);
        return reviews.stream().map(review -> new ReviewView(review.getId(), review.getSubmissionId(),
                review.getAssignmentId(), review.getReason(), review.getStatus(), review.getGradeAtOpen(),
                review.getPetitionMessage(), review.getEvidence(), review.getAiGrade(), review.getAiFeedback(),
                similar(review.getSimilarSubmissions()), review.getDecision(), review.getRationale(),
                review.getAdjustedGrade(), review.getAdjustedFeedback(), name.apply(review.getOpenedById()),
                review.getOpenedAt(), name.apply(review.getDecidedById()), review.getDecidedAt())).toList();
    }

    private List<Map<String, Object>> similar(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
