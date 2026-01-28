package com.open.spring.mvc.calendarBreak;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for CalendarBreak entities.
 * Handles database operations for breaks in the calendar.
 */
public interface CalendarBreakRepository extends JpaRepository<CalendarBreak, Long> {
    List<CalendarBreak> findByDate(LocalDate date);
    List<CalendarBreak> findAll();
    Optional<CalendarBreak> findById(Long id);
}
