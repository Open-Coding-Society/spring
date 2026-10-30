package com.open.spring.mvc.gradecalculator;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;

/**
 * Stores each student's grade calculator sheet and computes grades from it.
 *
 * The grade is total points: points earned over points possible across every assignment that
 * has a score, so every point counts the same. Entries without a score yet are skipped. The
 * current grade leaves out what-if entries and the projected grade includes them. The page
 * computes the same numbers live (assets/js/analytics/grade-calculator.js); keep the two in step.
 */
@Service
public class GradeCalculatorService {

    static final int MAX_ENTRIES = 300;
    static final int MAX_NAME = 100;
    static final double MAX_POINTS = 10_000;

    /**
     * One assignment. earned is null until it has a score. A what-if entry holds a score the
     * student is imagining, not one they received. Unknown fields, such as the category of a
     * sheet saved when the calculator had weighted categories, are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String name, Double earned, Double possible, Boolean whatIf) {
    }

    /** Everything the student entered. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sheet(List<Entry> entries) {
        static Sheet empty() {
            return new Sheet(List.of());
        }
    }

    /** The grades a sheet produces, in percent; null when nothing scored counts yet. */
    public record Grades(Double current, Double projected) {
    }

    /** Thrown when a sheet breaks a rule; the message says which, for the API to return. */
    public static class InvalidSheetException extends RuntimeException {
        public InvalidSheetException(String message) {
            super(message);
        }
    }

    private final GradeCalculatorJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GradeCalculatorService(GradeCalculatorJpaRepository repository) {
        this(repository, new ObjectMapper(), Clock.systemDefaultZone());
    }

    GradeCalculatorService(GradeCalculatorJpaRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The person's sheet, or an empty one when they have not saved any. */
    public Sheet load(Person person) {
        return repository.findByPerson(person).map(this::read).orElse(Sheet.empty());
    }

    /** When the person last saved their sheet; empty when never. */
    public Optional<LocalDateTime> updatedAt(Person person) {
        return repository.findByPerson(person).map(GradeCalculatorEntity::getUpdatedAt);
    }

    /** Replaces the person's sheet after checking it, and returns the sheet as stored. */
    @Transactional
    public Sheet save(Person person, Sheet sheet) {
        Sheet clean = validate(sheet);
        GradeCalculatorEntity entity = repository.findByPerson(person).orElseGet(() -> {
            GradeCalculatorEntity created = new GradeCalculatorEntity();
            created.setPerson(person);
            return created;
        });
        try {
            entity.setData(objectMapper.writeValueAsString(clean));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the grade calculator sheet", e);
        }
        entity.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(entity);
        return clean;
    }

    /** The person's current grade from their sheet, without what-if entries; empty when nothing counts yet. */
    public Optional<Double> currentGrade(Person person) {
        return Optional.ofNullable(calculate(load(person)).current());
    }

    /** Current and projected grades for a sheet; see the class comment for the rules. */
    public static Grades calculate(Sheet sheet) {
        return new Grades(grade(sheet, false), grade(sheet, true));
    }

    private static Double grade(Sheet sheet, boolean includeWhatIf) {
        double earned = 0;
        double possible = 0;
        for (Entry entry : sheet.entries()) {
            if (entry.earned() == null || entry.possible() == null || entry.possible() <= 0) {
                continue;
            }
            if (!includeWhatIf && Boolean.TRUE.equals(entry.whatIf())) {
                continue;
            }
            earned += entry.earned();
            possible += entry.possible();
        }
        return possible == 0 ? null : round(earned / possible * 100);
    }

    private Sheet read(GradeCalculatorEntity entity) {
        try {
            Sheet sheet = objectMapper.readValue(entity.getData(), Sheet.class);
            return new Sheet(sheet.entries() == null ? List.of() : sheet.entries());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored grade calculator sheet is not valid JSON", e);
        }
    }

    /** Checks the sheet and returns it with names trimmed and a missing list made empty. */
    static Sheet validate(Sheet sheet) {
        if (sheet == null) {
            throw new InvalidSheetException("sheet is required");
        }
        List<Entry> entries = sheet.entries() == null ? List.of() : sheet.entries();
        List<String> problems = new ArrayList<>();
        if (entries.size() > MAX_ENTRIES) {
            problems.add("at most " + MAX_ENTRIES + " assignments");
        }
        List<Entry> cleanEntries = new ArrayList<>();
        for (Entry entry : entries) {
            String name = trim(entry == null ? null : entry.name());
            if (name == null) {
                problems.add("every assignment needs a name");
                continue;
            }
            if (name.length() > MAX_NAME) {
                problems.add("assignment names must be " + MAX_NAME + " characters or fewer");
            }
            if (entry.possible() == null || entry.possible() <= 0 || entry.possible() > MAX_POINTS) {
                problems.add(name + " needs points possible above 0");
            }
            if (entry.earned() != null && (entry.earned() < 0 || entry.earned() > MAX_POINTS)) {
                problems.add(name + " has an invalid score");
            }
            cleanEntries.add(new Entry(name, entry.earned(), entry.possible(), Boolean.TRUE.equals(entry.whatIf())));
        }
        if (!problems.isEmpty()) {
            throw new InvalidSheetException(String.join("; ", problems.stream().distinct().toList()));
        }
        return new Sheet(cleanEntries);
    }

    private static String trim(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
