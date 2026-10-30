package com.open.spring.mvc.gradeforecast;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A student's growth goal: one skill, one small action they commit to, and a check-in date.
 *
 * The skill's three views (self, teacher, records) are copied when the goal starts and again
 * at check-in, so the student can see whether the action moved anything. A goal is active
 * until checked in; a person has at most one active goal.
 */
@Data
@NoArgsConstructor
@Entity(name = "GrowthGoal")
@Table(name = "growth_goal")
public class GrowthGoalEntity {

    /** How the student says the action went. */
    public enum Outcome { DID_IT, PARTLY, NOT_YET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    /** A skill name from GradeForecastService.SKILLS. */
    @Column(nullable = false)
    private String skill;

    /** The action, for example "Start each assignment the day it is posted". */
    @Column(nullable = false, length = 200)
    private String action;

    @Column(nullable = false)
    private LocalDate checkInDate;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // The skill's views when the goal started
    private Integer startSelf;
    private Double startTeacher;
    private Double startEvidence;

    // Filled at check-in
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Outcome outcome;

    @Column(length = 300)
    private String note;

    private LocalDateTime checkedInAt;
    private Integer endSelf;
    private Double endTeacher;
    private Double endEvidence;
}
