package com.open.spring.mvc.gradeforecast;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.evaluationAdmin.AdminEvaluation;
import com.open.spring.mvc.evaluationAdmin.AdminEvaluationRepository;
import com.open.spring.mvc.gradecalculator.GradeCalculatorService;
import com.open.spring.mvc.person.Person;

/**
 * Records grade forecasts and looks back at them.
 *
 * The retrospective is built from data, not from a reflection form: it checks how far the
 * model's forecasts and the student's own guesses were from the student's current grade,
 * which comes from their own grade calculator (the official gradebook has no API), compares
 * the student's latest self-ratings with the teacher's evaluation of the same skills and with
 * on-time submission evidence, shows what changed between the last two forecasts, and names
 * one skill to focus on. Signed errors are forecast minus actual, so a positive bias means
 * the forecasts ran high.
 */
@Service
public class GradeForecastService {

    /** A repeat Smart Predict with unchanged ratings inside this window edits the last forecast. */
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(15);

    /** A gap of at least this many rating points between two views of a skill is worth showing. */
    static final double NOTABLE_GAP = 1.0;

    /** The predictor's skill categories, in dashboard order, keyed by their JSON names. */
    static final Map<String, Function<GradeForecastEntity, Integer>> SKILLS = new LinkedHashMap<>();
    private static final Map<String, BiConsumer<GradeForecastEntity, Integer>> SKILL_SETTERS = new LinkedHashMap<>();
    private static final Map<String, Function<AdminEvaluation, Double>> TEACHER_RATINGS = new LinkedHashMap<>();

    static {
        skill("attendance", GradeForecastEntity::getAttendance, GradeForecastEntity::setAttendance, AdminEvaluation::getAttendance);
        skill("workHabits", GradeForecastEntity::getWorkHabits, GradeForecastEntity::setWorkHabits, AdminEvaluation::getWorkHabits);
        skill("behavior", GradeForecastEntity::getBehavior, GradeForecastEntity::setBehavior, AdminEvaluation::getBehavior);
        skill("timeliness", GradeForecastEntity::getTimeliness, GradeForecastEntity::setTimeliness, AdminEvaluation::getTimeliness);
        skill("techSense", GradeForecastEntity::getTechSense, GradeForecastEntity::setTechSense, AdminEvaluation::getTechSense);
        skill("techTalk", GradeForecastEntity::getTechTalk, GradeForecastEntity::setTechTalk, AdminEvaluation::getTechTalk);
        skill("techGrowth", GradeForecastEntity::getTechGrowth, GradeForecastEntity::setTechGrowth, AdminEvaluation::getTechGrowth);
        skill("advocacy", GradeForecastEntity::getAdvocacy, GradeForecastEntity::setAdvocacy, AdminEvaluation::getAdvocacy);
        skill("communication", GradeForecastEntity::getCommunication, GradeForecastEntity::setCommunication, AdminEvaluation::getCommunication);
        skill("integrity", GradeForecastEntity::getIntegrity, GradeForecastEntity::setIntegrity, AdminEvaluation::getIntegrity);
        skill("organization", GradeForecastEntity::getOrganization, GradeForecastEntity::setOrganization, AdminEvaluation::getOrganization);
    }

    private static void skill(String name, Function<GradeForecastEntity, Integer> getter,
            BiConsumer<GradeForecastEntity, Integer> setter, Function<AdminEvaluation, Double> teacher) {
        SKILLS.put(name, getter);
        SKILL_SETTERS.put(name, setter);
        TEACHER_RATINGS.put(name, teacher);
    }

    /** One forecast to record; ratings are keyed by the names in SKILLS and all are required. */
    public record Forecast(Map<String, Integer> ratings, Double modelGrade, Double studentGuess) {
    }

    /** How far one kind of forecast was from the student's current calculator grade. */
    public record Accuracy(int forecasts, Double bias, Double meanError) {
    }

    /** One forecast on the timeline. */
    public record Point(Long id, LocalDateTime createdAt, Double modelGrade, Double studentGuess, Double currentGrade) {
    }

    /**
     * Three views of one skill: the student's latest self-rating, the teacher's rating, and a
     * rating measured from records when there is one (evidenceDetail says what it counts).
     * The gaps are self minus the other view; changeSinceFirst compares the first and latest forecast.
     */
    public record SkillCheck(String skill, Integer self, Double teacher, Double teacherGap, Double evidence,
            String evidenceDetail, Double evidenceGap, Integer changeSinceFirst) {
    }

    /** What changed between the last two forecasts: the model's grade and every rating that moved. */
    public record Movement(LocalDateTime from, LocalDateTime to, Double modelGradeChange, Map<String, Integer> ratingChanges) {
    }

    /** The skill most worth working on, and why, in a sentence the page can show as is. */
    public record Focus(String skill, String reason) {
    }

    /**
     * How close each of the student's guesses was to their grade when they made it, oldest
     * first; judging your own work is a skill, and this shows whether it is improving. change
     * is the latest gap minus the first, so a negative change means the guesses got closer.
     */
    public record Calibration(List<Double> gaps, Double first, Double latest, Double change) {
    }

    /** Everything the Forecast Retrospective panel shows; empty lists and nulls until there is data. */
    public record Retrospective(Double currentGrade, List<Point> forecasts, Accuracy model, Accuracy guess,
            Calibration calibration, List<SkillCheck> skills, Movement lastChange, Focus focus) {
    }

    /** Thrown when a forecast breaks a rule; the message says which, for the API to return. */
    public static class InvalidForecastException extends RuntimeException {
        public InvalidForecastException(String message) {
            super(message);
        }
    }

    private final GradeForecastJpaRepository repository;
    private final GradeCalculatorService calculatorService;
    private final AdminEvaluationRepository teacherEvaluationRepository;
    private final AssignmentSubmissionJPA submissionRepository;
    private final Clock clock;

    @Autowired
    public GradeForecastService(GradeForecastJpaRepository repository, GradeCalculatorService calculatorService,
            AdminEvaluationRepository teacherEvaluationRepository, AssignmentSubmissionJPA submissionRepository) {
        this(repository, calculatorService, teacherEvaluationRepository, submissionRepository, Clock.systemDefaultZone());
    }

    GradeForecastService(GradeForecastJpaRepository repository, GradeCalculatorService calculatorService,
            AdminEvaluationRepository teacherEvaluationRepository, AssignmentSubmissionJPA submissionRepository, Clock clock) {
        this.repository = repository;
        this.calculatorService = calculatorService;
        this.teacherEvaluationRepository = teacherEvaluationRepository;
        this.submissionRepository = submissionRepository;
        this.clock = clock;
    }

    /**
     * Saves a forecast stamped with the student's current calculator grade. Pressing Smart Predict
     * again with the same ratings within REPEAT_WINDOW edits the last forecast instead of
     * adding a near-duplicate.
     */
    @Transactional
    public GradeForecastEntity record(Person person, Forecast forecast) {
        validate(forecast);
        LocalDateTime now = LocalDateTime.now(clock);
        GradeForecastEntity entity = repository.findFirstByPersonOrderByCreatedAtDescIdDesc(person)
                .filter(last -> last.getCreatedAt().isAfter(now.minus(REPEAT_WINDOW)) && sameRatings(last, forecast.ratings()))
                .orElseGet(() -> {
                    GradeForecastEntity created = new GradeForecastEntity();
                    created.setPerson(person);
                    SKILL_SETTERS.forEach((name, setter) -> setter.accept(created, forecast.ratings().get(name)));
                    return created;
                });
        entity.setModelGrade(round(forecast.modelGrade()));
        entity.setStudentGuess(forecast.studentGuess() == null ? null : round(forecast.studentGuess()));
        entity.setCurrentGrade(calculatorService.currentGrade(person).orElse(null));
        entity.setCreatedAt(now);
        return repository.save(entity);
    }

    /** Deletes one of the person's own forecasts; false when they have none with this id. */
    @Transactional
    public boolean delete(Person person, Long id) {
        Optional<GradeForecastEntity> forecast = repository.findByIdAndPerson(id, person);
        forecast.ifPresent(repository::delete);
        return forecast.isPresent();
    }

    /** Builds the person's retrospective from their forecasts, calculator grade, teacher evaluation and submissions. */
    public Retrospective retrospective(Person person) {
        List<GradeForecastEntity> history = repository.findByPersonOrderByCreatedAtAscIdAsc(person);
        AdminEvaluation teacher = person.getId() == null ? null
                : teacherEvaluationRepository.findByUserId(person.getId().intValue()).orElse(null);
        return build(history, calculatorService.currentGrade(person).orElse(null), teacher, timelinessEvidence(person).orElse(null));
    }

    /** The person's three views of one skill right now; see SkillCheck. */
    public SkillCheck skillCheck(Person person, String skill) {
        return retrospective(person).skills().stream().filter(s -> s.skill().equals(skill)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown skill " + skill));
    }

    /** On-time evidence for Timeliness: the rating it implies and a sentence saying what was counted. */
    record Evidence(double rating, String detail) {
    }

    static Retrospective build(List<GradeForecastEntity> history, Double currentGrade, AdminEvaluation teacher,
            Evidence timeliness) {
        List<Point> points = history.stream()
                .map(f -> new Point(f.getId(), f.getCreatedAt(), f.getModelGrade(), f.getStudentGuess(), f.getCurrentGrade()))
                .toList();
        Accuracy model = accuracy(history.stream().map(GradeForecastEntity::getModelGrade).toList(), currentGrade);
        Accuracy guess = accuracy(history.stream().map(GradeForecastEntity::getStudentGuess).toList(), currentGrade);

        GradeForecastEntity first = history.isEmpty() ? null : history.get(0);
        GradeForecastEntity latest = history.isEmpty() ? null : history.get(history.size() - 1);
        List<SkillCheck> skills = new ArrayList<>();
        for (Map.Entry<String, Function<GradeForecastEntity, Integer>> skill : SKILLS.entrySet()) {
            String name = skill.getKey();
            Integer self = latest == null ? null : skill.getValue().apply(latest);
            Double teacherRating = teacher == null ? null : TEACHER_RATINGS.get(name).apply(teacher);
            Evidence evidence = "timeliness".equals(name) ? timeliness : null;
            Integer before = first == null ? null : skill.getValue().apply(first);
            skills.add(new SkillCheck(name, self, teacherRating, gap(self, teacherRating),
                    evidence == null ? null : evidence.rating(), evidence == null ? null : evidence.detail(),
                    gap(self, evidence == null ? null : evidence.rating()),
                    history.size() < 2 || self == null || before == null ? null : self - before));
        }

        Movement lastChange = null;
        if (history.size() >= 2) {
            GradeForecastEntity previous = history.get(history.size() - 2);
            Map<String, Integer> changes = new LinkedHashMap<>();
            SKILLS.forEach((name, getter) -> {
                Integer from = getter.apply(previous);
                Integer to = getter.apply(latest);
                if (from != null && to != null && !from.equals(to)) {
                    changes.put(name, to - from);
                }
            });
            lastChange = new Movement(previous.getCreatedAt(), latest.getCreatedAt(),
                    round(latest.getModelGrade() - previous.getModelGrade()), changes);
        }

        return new Retrospective(currentGrade, points, model, guess, calibration(history), skills, lastChange, focus(skills));
    }

    /**
     * Picks one skill: the one the student overrates most against the teacher, then against
     * evidence, and otherwise their lowest self-rating. Ties go to the earlier skill.
     */
    static Focus focus(List<SkillCheck> skills) {
        SkillCheck overTeacher = null;
        SkillCheck overEvidence = null;
        SkillCheck lowest = null;
        for (SkillCheck s : skills) {
            if (s.teacherGap() != null && s.teacherGap() >= NOTABLE_GAP
                    && (overTeacher == null || s.teacherGap() > overTeacher.teacherGap())) {
                overTeacher = s;
            }
            if (s.evidenceGap() != null && s.evidenceGap() >= NOTABLE_GAP
                    && (overEvidence == null || s.evidenceGap() > overEvidence.evidenceGap())) {
                overEvidence = s;
            }
            if (s.self() != null && (lowest == null || s.self() < lowest.self())) {
                lowest = s;
            }
        }
        if (overTeacher != null) {
            return new Focus(overTeacher.skill(), String.format(
                    "You rate yourself %d, but your teacher rates you %s. Ask what would earn the higher score.",
                    overTeacher.self(), trim(overTeacher.teacher())));
        }
        if (overEvidence != null) {
            return new Focus(overEvidence.skill(), String.format(
                    "You rate yourself %d, but your records point to %s (%s).",
                    overEvidence.self(), trim(overEvidence.evidence()), overEvidence.evidenceDetail()));
        }
        if (lowest != null) {
            return new Focus(lowest.skill(), String.format(
                    "Your views agree, so the biggest lift is your lowest rating (%d).", lowest.self()));
        }
        return null;
    }

    /**
     * Timeliness implied by the person's own submissions: the share of assignments whose latest
     * submission was not marked late, mapped onto 1 to 5. Empty when they have submitted nothing.
     */
    Optional<Evidence> timelinessEvidence(Person person) {
        Map<Long, AssignmentSubmission> latestByAssignment = new HashMap<>();
        for (AssignmentSubmission submission : submissionRepository.findBySubmitter(person)) {
            if (submission.getAssignment() == null) {
                continue;
            }
            latestByAssignment.merge(submission.getAssignment().getId(), submission,
                    (a, b) -> Objects.compare(a.getId(), b.getId(), Long::compare) >= 0 ? a : b);
        }
        if (latestByAssignment.isEmpty()) {
            return Optional.empty();
        }
        int total = latestByAssignment.size();
        long onTime = latestByAssignment.values().stream().filter(s -> !Boolean.TRUE.equals(s.getIsLate())).count();
        double rating = round(1 + 4.0 * onTime / total);
        return Optional.of(new Evidence(rating, onTime + " of " + total + " submissions on time"));
    }

    private static Calibration calibration(List<GradeForecastEntity> history) {
        List<Double> gaps = history.stream()
                .filter(f -> f.getStudentGuess() != null && f.getCurrentGrade() != null)
                .map(f -> round(Math.abs(f.getStudentGuess() - f.getCurrentGrade())))
                .toList();
        if (gaps.isEmpty()) {
            return new Calibration(gaps, null, null, null);
        }
        Double first = gaps.get(0);
        Double latest = gaps.get(gaps.size() - 1);
        return new Calibration(gaps, first, latest, gaps.size() < 2 ? null : round(latest - first));
    }

    private static Accuracy accuracy(List<Double> forecasts, Double actual) {
        List<Double> made = forecasts.stream().filter(Objects::nonNull).toList();
        if (made.isEmpty() || actual == null) {
            return new Accuracy(made.size(), null, null);
        }
        double signed = 0;
        double absolute = 0;
        for (double forecast : made) {
            signed += forecast - actual;
            absolute += Math.abs(forecast - actual);
        }
        return new Accuracy(made.size(), round(signed / made.size()), round(absolute / made.size()));
    }

    private static boolean sameRatings(GradeForecastEntity forecast, Map<String, Integer> ratings) {
        return SKILLS.entrySet().stream()
                .allMatch(skill -> Objects.equals(skill.getValue().apply(forecast), ratings.get(skill.getKey())));
    }

    private static void validate(Forecast forecast) {
        if (forecast == null || forecast.ratings() == null) {
            throw new InvalidForecastException("ratings are required");
        }
        List<String> problems = new ArrayList<>();
        for (String name : SKILLS.keySet()) {
            Integer rating = forecast.ratings().get(name);
            if (rating == null) {
                problems.add(name + " is required");
            } else if (rating < 1 || rating > 5) {
                problems.add(name + " must be from 1 to 5");
            }
        }
        forecast.ratings().keySet().stream().filter(name -> !SKILLS.containsKey(name))
                .forEach(name -> problems.add("unknown skill " + name));
        if (forecast.modelGrade() == null) {
            problems.add("modelGrade is required");
        } else if (forecast.modelGrade() < 0 || forecast.modelGrade() > 100) {
            problems.add("modelGrade must be from 0 to 100");
        }
        if (forecast.studentGuess() != null && (forecast.studentGuess() < 0 || forecast.studentGuess() > 100)) {
            problems.add("studentGuess must be from 0 to 100");
        }
        if (!problems.isEmpty()) {
            throw new InvalidForecastException(String.join("; ", problems));
        }
    }

    private static Double gap(Integer self, Double other) {
        return self == null || other == null ? null : round(self - other);
    }

    private static String trim(Double value) {
        return value == Math.rint(value) ? String.valueOf(value.intValue()) : String.valueOf(value);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
