package com.open.spring.mvc.assignments;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.PersonJpaRepository;

/** Closes an assignment (see {@link AssignmentClosureService}). */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentClosureApiRestController {

    public record ClosureResponse(GradingOutcome outcome, AssignmentClosureService.Summary summary) {
    }

    private final AssignmentClosureService closureService;
    private final PersonJpaRepository personRepo;

    public AssignmentClosureApiRestController(AssignmentClosureService closureService, PersonJpaRepository personRepo) {
        this.closureService = closureService;
        this.personRepo = personRepo;
    }

    @PostMapping("/{assignmentId}/close")
    public ResponseEntity<?> close(@PathVariable Long assignmentId, @AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, teacher -> {
            AssignmentClosureService.Result result = closureService.close(assignmentId, teacher);
            return ResponseEntity.status(GradingResponse.statusOf(result.outcome()))
                    .body(new ClosureResponse(result.outcome(), result.summary()));
        });
    }
}
