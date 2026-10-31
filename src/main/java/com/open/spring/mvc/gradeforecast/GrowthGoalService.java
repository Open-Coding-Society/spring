package com.open.spring.mvc.gradeforecast;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.gradeforecast.GradeForecastService.SkillCheck;
import com.open.spring.mvc.gradeforecast.GrowthGoalEntity.Outcome;
import com.open.spring.mvc.person.Person;

/**
 * Growth goals: the student picks one skill and one small action, then checks in on a date.
 *
 * This is what turns the forecast retrospective into something a student acts on. Starting
 * and checking in both copy the skill's current self, teacher and records ratings, so a
 * finished goal shows what changed while the student was working on it. A goal counts as
 * improved when the teacher's rating or the records rating went up; the student's own rating
 * is not evidence of improvement on its own. Goals can also target skills the predictor does
 * not rate, such as AI orchestration; those goals have no ratings to compare.
 */
@Service
public class GrowthGoalService {

    static final int DEFAULT_DAYS = 14;
    static final int MAX_DAYS = 60;
    static final int MAX_ACTION = 200;
    static final int MAX_NOTE = 300;

    /** Skills a goal can target: the predictor's skills plus ones it does not rate. */
    static final Set<String> GOAL_SKILLS;

    static {
        Set<String> skills = new LinkedHashSet<>(GradeForecastService.SKILLS.keySet());
        skills.add("aiOrchestration");
        GOAL_SKILLS = Collections.unmodifiableSet(skills);
    }

    /** One goal for the page; now holds the skill's live views while the goal is active. */
    public record GoalView(Long id, String skill, String action, LocalDate checkInDate, boolean due,
            LocalDateTime createdAt, Integer startSelf, Double startTeacher, Double startEvidence,
            Outcome outcome, String note, LocalDateTime checkedInAt, Integer endSelf, Double endTeacher,
            Double endEvidence, Boolean improved, SkillCheck now) {
    }

    /** The active goal (or null), finished goals newest first, and simple totals. */
    public record Goals(GoalView active, List<GoalView> finished, int completed, int improved) {
    }

    /** Thrown when a goal request breaks a rule; the message says which. */
    public static class InvalidGoalException extends RuntimeException {
        public InvalidGoalException(String message) {
            super(message);
        }
    }

    /** Thrown when a new goal is started while another is still active. */
    public static class GoalConflictException extends RuntimeException {
        public GoalConflictException(String message) {
            super(message);
        }
    }

    private final GrowthGoalJpaRepository repository;
    private final GradeForecastService forecastService;
    private final Clock clock;

    @Autowired
    public GrowthGoalService(GrowthGoalJpaRepository repository, GradeForecastService forecastService) {
        this(repository, forecastService, Clock.systemDefaultZone());
    }

    GrowthGoalService(GrowthGoalJpaRepository repository, GradeForecastService forecastService, Clock clock) {
        this.repository = repository;
        this.forecastService = forecastService;
        this.clock = clock;
    }

    /** The person's goals; see Goals. */
    public Goals list(Person person) {
        List<GrowthGoalEntity> all = repository.findByPersonOrderByCreatedAtDescIdDesc(person);
        GoalView active = null;
        List<GoalView> finished = new ArrayList<>();
        for (GrowthGoalEntity goal : all) {
            if (goal.getCheckedInAt() == null) {
                if (active == null) {
                    active = view(goal, views(person, goal.getSkill()));
                }
            } else {
                finished.add(view(goal, null));
            }
        }
        int improved = (int) finished.stream().filter(g -> Boolean.TRUE.equals(g.improved())).count();
        return new Goals(active, finished, finished.size(), improved);
    }

    /**
     * Starts a goal. checkInDate defaults to DEFAULT_DAYS from today and must fall within
     * MAX_DAYS. Fails with GoalConflictException while another goal is active.
     */
    @Transactional
    public GoalView start(Person person, String skill, String action, LocalDate checkInDate) {
        LocalDate today = LocalDate.now(clock);
        String cleanAction = action == null ? "" : action.strip();
        if (skill == null || !GOAL_SKILLS.contains(skill)) {
            throw new InvalidGoalException("choose one of the listed skills");
        }
        if (cleanAction.length() < 3) {
            throw new InvalidGoalException("describe the action you will take");
        }
        if (cleanAction.length() > MAX_ACTION) {
            throw new InvalidGoalException("keep the action to " + MAX_ACTION + " characters");
        }
        LocalDate date = checkInDate == null ? today.plusDays(DEFAULT_DAYS) : checkInDate;
        if (!date.isAfter(today) || date.isAfter(today.plusDays(MAX_DAYS))) {
            throw new InvalidGoalException("pick a check-in date within the next " + MAX_DAYS + " days");
        }
        if (repository.findFirstByPersonAndCheckedInAtIsNull(person).isPresent()) {
            throw new GoalConflictException("check in on your current goal before starting a new one");
        }
        SkillCheck start = views(person, skill);
        GrowthGoalEntity goal = new GrowthGoalEntity();
        goal.setPerson(person);
        goal.setSkill(skill);
        goal.setAction(cleanAction);
        goal.setCheckInDate(date);
        goal.setCreatedAt(LocalDateTime.now(clock));
        goal.setStartSelf(start.self());
        goal.setStartTeacher(start.teacher());
        goal.setStartEvidence(start.evidence());
        GrowthGoalEntity saved = repository.save(goal);
        return view(saved, start);
    }

    /** Checks in on the person's active goal; empty when they have no active goal with this id. */
    @Transactional
    public Optional<GoalView> checkIn(Person person, Long id, Outcome outcome, String note) {
        if (outcome == null) {
            throw new InvalidGoalException("say how it went");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new InvalidGoalException("keep the note to " + MAX_NOTE + " characters");
        }
        Optional<GrowthGoalEntity> found = repository.findByIdAndPerson(id, person).filter(g -> g.getCheckedInAt() == null);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        GrowthGoalEntity goal = found.get();
        SkillCheck end = views(person, goal.getSkill());
        goal.setOutcome(outcome);
        goal.setNote(cleanNote);
        goal.setCheckedInAt(LocalDateTime.now(clock));
        goal.setEndSelf(end.self());
        goal.setEndTeacher(end.teacher());
        goal.setEndEvidence(end.evidence());
        return Optional.of(view(repository.save(goal), null));
    }

    /** Deletes one of the person's goals, active or finished; false when they have none with this id. */
    @Transactional
    public boolean delete(Person person, Long id) {
        Optional<GrowthGoalEntity> goal = repository.findByIdAndPerson(id, person);
        goal.ifPresent(repository::delete);
        return goal.isPresent();
    }

    /** The skill's self, teacher and records views; all empty for a skill the predictor does not rate. */
    private SkillCheck views(Person person, String skill) {
        if (!GradeForecastService.SKILLS.containsKey(skill)) {
            return new SkillCheck(skill, null, null, null, null, null, null, null);
        }
        return forecastService.skillCheck(person, skill);
    }

    private GoalView view(GrowthGoalEntity g, SkillCheck now) {
        boolean finished = g.getCheckedInAt() != null;
        Boolean improved = finished ? rose(g.getStartTeacher(), g.getEndTeacher()) || rose(g.getStartEvidence(), g.getEndEvidence()) : null;
        boolean due = !finished && !LocalDate.now(clock).isBefore(g.getCheckInDate());
        return new GoalView(g.getId(), g.getSkill(), g.getAction(), g.getCheckInDate(), due, g.getCreatedAt(),
                g.getStartSelf(), g.getStartTeacher(), g.getStartEvidence(), g.getOutcome(), g.getNote(),
                g.getCheckedInAt(), g.getEndSelf(), g.getEndTeacher(), g.getEndEvidence(), improved, now);
    }

    private static boolean rose(Double before, Double after) {
        return before != null && after != null && after > before;
    }
}
