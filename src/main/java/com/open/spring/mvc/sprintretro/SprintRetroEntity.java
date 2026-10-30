package com.open.spring.mvc.sprintretro;

import java.time.LocalDate;
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
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A sprint a teacher opened for reflection. Every student reflects on it with the Ground 0
 * evaluation items in SprintRetroTemplate; sprints are ordered by start date so a student's
 * reflections read as a year of growth.
 */
@Data
@NoArgsConstructor
@Entity(name = "SprintRetro")
@Table(name = "sprint_retro")
public class SprintRetroEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    /** The teacher or admin who created the sprint. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @JsonIgnore
    private Person createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
