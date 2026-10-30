package com.open.spring.mvc.gradeforecast;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One grade forecast: the self-ratings a student gave the Grade Predictor, the grade the model
 * predicted from them, the student's own guess, and their grade calculator's grade at that moment.
 *
 * The student evaluation row is overwritten on every Smart Predict, so before this table the
 * dashboard could not look back at what it had predicted. Keeping each forecast lets the
 * retrospective compare past forecasts with the grade the student went on to have.
 */
@Data
@NoArgsConstructor
@Entity(name = "GradeForecast")
@Table(name = "grade_forecast")
public class GradeForecastEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    // Self-ratings, 1 to 5, in the Grade Predictor's categories
    private Integer attendance;
    private Integer workHabits;
    private Integer behavior;
    private Integer timeliness;
    private Integer techSense;
    private Integer techTalk;
    private Integer techGrowth;
    private Integer advocacy;
    private Integer communication;
    private Integer integrity;
    private Integer organization;

    /** The Smart Predict model's grade, 0 to 100. */
    @Column(nullable = false)
    private Double modelGrade;

    /** The grade the student expected before seeing the model's, 0 to 100; null when skipped. */
    private Double studentGuess;

    /** The student's grade calculator result when the forecast was made; null when it had no scores yet. */
    private Double currentGrade;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
