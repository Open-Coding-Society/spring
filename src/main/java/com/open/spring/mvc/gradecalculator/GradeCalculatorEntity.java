package com.open.spring.mvc.gradecalculator;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A student's grade calculator sheet: the assignments and scores they copied from
 * their gradebook, plus any what-if scores, stored as JSON. One sheet per person.
 *
 * Grades are entered by the student because the official gradebook has no API this app can
 * read; the sheet is their own working copy, not a record of truth.
 */
@Data
@NoArgsConstructor
@Entity(name = "GradeCalculator")
@Table(name = "grade_calculator", uniqueConstraints = @UniqueConstraint(columnNames = "person_id"))
public class GradeCalculatorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    /** The sheet as JSON, in the shape of GradeCalculatorService.Sheet. */
    @Column(columnDefinition = "text", nullable = false)
    private String data;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
