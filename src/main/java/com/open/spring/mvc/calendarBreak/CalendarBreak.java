package com.open.spring.mvc.calendarBreak;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity representing a calendar break.
 * When a break is created for a date, all events on that date are moved to the next non-break day.
 */
@Entity
@Table(name = "calendar_breaks")
public class CalendarBreak {
    // POJO
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private String name; // Break name (e.g., "Spring Break", "Holiday")
    private String description; // Break description

    // Default constructor
    public CalendarBreak() {
    }

    // Constructor with date, name, and description
    public CalendarBreak(LocalDate date, String name, String description) {
        this.date = date;
        this.name = name;
        this.description = description;
    }

    // Constructor with date and name
    public CalendarBreak(LocalDate date, String name) {
        this.date = date;
        this.name = name;
        this.description = "";
    }

    // Constructor with date only
    public CalendarBreak(LocalDate date) {
        this.date = date;
        this.name = "Break";
        this.description = "";
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
