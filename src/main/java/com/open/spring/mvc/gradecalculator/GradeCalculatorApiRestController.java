package com.open.spring.mvc.gradecalculator;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.gradecalculator.GradeCalculatorService.Grades;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.InvalidSheetException;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService.Sheet;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * REST API for a student's own grade calculator under /api/grade-calculator.
 *
 * Each signed-in account reads and replaces only its own sheet; the account comes from the
 * JWT cookie. Access otherwise follows the shared /api/** rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/grade-calculator")
public class GradeCalculatorApiRestController {

    /** The stored sheet with the grades it produces; updatedAt is null until the first save. */
    public record SheetResponse(Sheet sheet, Grades grades, LocalDateTime updatedAt) {
    }

    private final GradeCalculatorService calculatorService;
    private final PersonJpaRepository personRepository;

    public GradeCalculatorApiRestController(GradeCalculatorService calculatorService, PersonJpaRepository personRepository) {
        this.calculatorService = calculatorService;
        this.personRepository = personRepository;
    }

    /** GET /api/grade-calculator - the caller's sheet and grades; an empty sheet before the first save. */
    @GetMapping
    public SheetResponse mine(@AuthenticationPrincipal UserDetails user) {
        Person person = caller(user);
        Sheet sheet = calculatorService.load(person);
        return new SheetResponse(sheet, GradeCalculatorService.calculate(sheet), calculatorService.updatedAt(person).orElse(null));
    }

    /** PUT /api/grade-calculator - replaces the caller's sheet; 400 on invalid input. */
    @PutMapping
    public SheetResponse save(@AuthenticationPrincipal UserDetails user, @RequestBody Sheet sheet) {
        Person person = caller(user);
        Sheet saved = calculatorService.save(person, sheet);
        return new SheetResponse(saved, GradeCalculatorService.calculate(saved), calculatorService.updatedAt(person).orElse(null));
    }

    @ExceptionHandler(InvalidSheetException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidSheetException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private Person caller(UserDetails user) {
        Person person = user == null ? null : personRepository.findByUid(user.getUsername());
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use the grade calculator");
        }
        return person;
    }
}
