package com.open.spring.mvc.evaluationStudent;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/student-evaluation")
public class StudentEvaluationController {

    @Autowired
    private StudentEvaluationRepository studentEvaluationRepository;
    @Autowired
    private PersonJpaRepository personJpaRepository;

    private static final ResponseEntity<?> FORBIDDEN =
        ResponseEntity.status(403).body(Map.of("error", "You can only access your own evaluation"));

    /** A student reaches only their own evaluation; a teacher or admin reaches any. */
    private boolean canAccess(UserDetails caller, Integer userId) {
        if (caller == null || userId == null) return false;
        if (caller.getAuthorities().stream().anyMatch(a ->
                "ROLE_TEACHER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()))) {
            return true;
        }
        Person person = personJpaRepository.findByUid(caller.getUsername());
        return person != null && person.getId() != null && person.getId().longValue() == userId.longValue();
    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentEvaluationRequest {
        @JsonProperty("user_id")
        private Integer userId;
        @JsonProperty("attendance")
        private Double attendance;
        @JsonProperty("work_habits")
        private Double workHabits;
        @JsonProperty("behavior")
        private Double behavior;
        @JsonProperty("timeliness")
        private Double timeliness;
        @JsonProperty("tech_sense")
        private Double techSense;
        @JsonProperty("tech_talk")
        private Double techTalk;
        @JsonProperty("tech_growth")
        private Double techGrowth;
        @JsonProperty("advocacy")
        private Double advocacy;
        @JsonProperty("communication")
        private Double communication;
        @JsonProperty("integrity")
        private Double integrity;
        @JsonProperty("organization")
        private Double organization;
    }

    @PostMapping("/post")
    public ResponseEntity<?> createEvaluation(@AuthenticationPrincipal UserDetails caller, @RequestBody StudentEvaluationRequest req) {
        if (req.getUserId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        if (!canAccess(caller, req.getUserId())) return FORBIDDEN;
        if (studentEvaluationRepository.findByUserId(req.getUserId()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Evaluation for this userId already exists"));
        }
        StudentEvaluation evaluation = new StudentEvaluation(
            req.getUserId(),
            req.getAttendance(),
            req.getWorkHabits(),
            req.getBehavior(),
            req.getTimeliness(),
            req.getTechSense(),
            req.getTechTalk(),
            req.getTechGrowth(),
            req.getAdvocacy(),
            req.getCommunication(),
            req.getIntegrity(),
            req.getOrganization()
        );
        StudentEvaluation saved = studentEvaluationRepository.save(evaluation);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "id", saved.getId(),
            "createdAt", saved.getCreatedAt(),
            "evaluation", saved
        ));
    }

    @GetMapping("/get/{user_id}")
    public ResponseEntity<?> getEvaluationByUserId(@AuthenticationPrincipal UserDetails caller, @PathVariable("user_id") Integer userId) {
        if (!canAccess(caller, userId)) return FORBIDDEN;
        Optional<StudentEvaluation> optionalEval = studentEvaluationRepository.findByUserId(userId);
        if (optionalEval.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Evaluation not found"));
        }
        return ResponseEntity.ok(Map.of(
            "evaluation", optionalEval.get()
        ));
    }

    @PutMapping("/update/{user_id}")
    public ResponseEntity<?> updateEvaluation(@AuthenticationPrincipal UserDetails caller, @PathVariable("user_id") Integer userId, @RequestBody StudentEvaluationRequest req) {
        if (!canAccess(caller, userId)) return FORBIDDEN;
        // Moving an evaluation to another user needs access to that user too.
        if (req.getUserId() != null && !canAccess(caller, req.getUserId())) return FORBIDDEN;
        Optional<StudentEvaluation> optionalEval = studentEvaluationRepository.findByUserId(userId);
        if (optionalEval.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Evaluation not found"));
        }
        StudentEvaluation evaluation = optionalEval.get();
        // Update fields if present in request
        if (req.getUserId() != null) evaluation.setUserId(req.getUserId());
        if (req.getAttendance() != null) evaluation.setAttendance(req.getAttendance());
        if (req.getWorkHabits() != null) evaluation.setWorkHabits(req.getWorkHabits());
        if (req.getBehavior() != null) evaluation.setBehavior(req.getBehavior());
        if (req.getTimeliness() != null) evaluation.setTimeliness(req.getTimeliness());
        if (req.getTechSense() != null) evaluation.setTechSense(req.getTechSense());
        if (req.getTechTalk() != null) evaluation.setTechTalk(req.getTechTalk());
        if (req.getTechGrowth() != null) evaluation.setTechGrowth(req.getTechGrowth());
        if (req.getAdvocacy() != null) evaluation.setAdvocacy(req.getAdvocacy());
        if (req.getCommunication() != null) evaluation.setCommunication(req.getCommunication());
        if (req.getIntegrity() != null) evaluation.setIntegrity(req.getIntegrity());
        if (req.getOrganization() != null) evaluation.setOrganization(req.getOrganization());
        StudentEvaluation saved = studentEvaluationRepository.save(evaluation);
        return ResponseEntity.ok(Map.of(
            "status", "updated",
            "id", saved.getId(),
            "evaluation", saved
        ));
    }

    @DeleteMapping("/delete/{user_id}")
    public ResponseEntity<?> deleteEvaluation(@AuthenticationPrincipal UserDetails caller, @PathVariable("user_id") Integer userId) {
        if (!canAccess(caller, userId)) return FORBIDDEN;
        Optional<StudentEvaluation> optionalEval = studentEvaluationRepository.findByUserId(userId);
        if (optionalEval.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Evaluation not found"));
        }
        studentEvaluationRepository.deleteById(optionalEval.get().getId());
        return ResponseEntity.ok(Map.of(
            "status", "deleted",
            "user_id", userId
        ));
    }
}
