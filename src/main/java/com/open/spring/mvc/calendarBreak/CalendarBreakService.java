package com.open.spring.mvc.calendarBreak;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.slack.CalendarEvent;
import com.open.spring.mvc.slack.CalendarEventRepository;

/**
 * Service for managing calendar breaks.
 * Handles creation, deletion, and retrieval of breaks.
 * When a break is created, all events for that date are moved to the next non-break day.
 */
@Service
public class CalendarBreakService {

    @Autowired
    private CalendarBreakRepository calendarBreakRepository;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    private static final int MAX_DAYS_AHEAD = 365;

    /**
     * Create a break for a specific date.
     * Moves all events from that date to the next non-break day.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @param description Description of the break
     * @param moveToNextNonBreakDay Whether to move events to next non-break day
     * @return The created CalendarBreak entity
     */
    @Transactional
    public CalendarBreak createBreak(LocalDate date, String name, String description, boolean moveToNextNonBreakDay) {
        // Validate inputs
        if (name == null || name.trim().isEmpty()) {
            name = "Break";
        }
        if (description == null) {
            description = "";
        }

        // Check if a break already exists for this date
        List<CalendarBreak> existingBreaks = calendarBreakRepository.findByDate(date);
        if (!existingBreaks.isEmpty()) {
            // Break already exists, just return it
            return existingBreaks.get(0);
        }

        // Move all events from this date if requested
        if (moveToNextNonBreakDay) {
            List<CalendarEvent> eventsOnDate = calendarEventRepository.findByDate(date);
            LocalDate nextNonBreakDay = findNextNonBreakDay(date);

            for (CalendarEvent event : eventsOnDate) {
                event.setDate(nextNonBreakDay);
                calendarEventRepository.save(event);
            }
        }

        // Create and save the break
        CalendarBreak breakRecord = new CalendarBreak(date, name, description);
        return calendarBreakRepository.save(breakRecord);
    }

    /**
     * Create a break with name and description.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @param description Description of the break
     * @return The created CalendarBreak entity
     */
    public CalendarBreak createBreak(LocalDate date, String name, String description) {
        return createBreak(date, name, description, false);
    }

    /**
     * Create a break with a simple name.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @return The created CalendarBreak entity
     */
    public CalendarBreak createBreak(LocalDate date, String name) {
        return createBreak(date, name, "", false);
    }

    /**
     * Create a break with default name.
     * 
     * @param date The date of the break
     * @return The created CalendarBreak entity
     */
    public CalendarBreak createBreak(LocalDate date) {
        return createBreak(date, "Break", "", false);
    }

    /**
     * Find the next non-break day starting from the given date.
     * Skips the break day itself and checks subsequent days.
     * 
     * @param date The starting date
     * @return The first date that doesn't have a break
     */
    public LocalDate findNextNonBreakDay(LocalDate date) {
        LocalDate currentDate = date.plusDays(1); // Start from the next day
        int daysChecked = 0;

        while (daysChecked < MAX_DAYS_AHEAD) {
            if (!isBreakDay(currentDate)) {
                return currentDate;
            }
            currentDate = currentDate.plusDays(1);
            daysChecked++;
        }

        // Fallback: return a date 365 days from now if no non-break day found
        return date.plusDays(MAX_DAYS_AHEAD);
    }

    /**
     * Update a break's name and description.
     * 
     * @param id The ID of the break to update
     * @param name The new name
     * @param description The new description
     * @return The updated CalendarBreak or null if not found
     */
    @Transactional
    public CalendarBreak updateBreak(Long id, String name, String description) {
        CalendarBreak breakRecord = calendarBreakRepository.findById(id).orElse(null);
        if (breakRecord != null) {
            if (name != null && !name.trim().isEmpty()) {
                breakRecord.setName(name);
            }
            if (description != null) {
                breakRecord.setDescription(description);
            }
            return calendarBreakRepository.save(breakRecord);
        }
        return null;
    }

    /**
     * Delete a break by its ID.
     * Does NOT move events back (they stay on their rescheduled dates).
     * 
     * @param id The ID of the break to delete
     * @return true if the break was deleted, false if not found
     */
    @Transactional
    public boolean deleteBreakById(Long id) {
        if (calendarBreakRepository.existsById(id)) {
            calendarBreakRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Delete a break by date.
     * 
     * @param date The date of the break to delete
     * @return true if a break was deleted, false if not found
     */
    @Transactional
    public boolean deleteBreakByDate(LocalDate date) {
        List<CalendarBreak> breaks = calendarBreakRepository.findByDate(date);
        if (!breaks.isEmpty()) {
            for (CalendarBreak breakRecord : breaks) {
                calendarBreakRepository.delete(breakRecord);
            }
            return true;
        }
        return false;
    }

    /**
     * Get all breaks for a specific date.
     * 
     * @param date The date to check
     * @return List of breaks for that date
     */
    public List<CalendarBreak> getBreaksByDate(LocalDate date) {
        return calendarBreakRepository.findByDate(date);
    }

    /**
     * Get all breaks.
     * 
     * @return List of all breaks
     */
    public List<CalendarBreak> getAllBreaks() {
        return calendarBreakRepository.findAll();
    }

    /**
     * Check if a date is a break day.
     * 
     * @param date The date to check
     * @return true if there is a break on this date, false otherwise
     */
    public boolean isBreakDay(LocalDate date) {
        List<CalendarBreak> breaks = calendarBreakRepository.findByDate(date);
        return !breaks.isEmpty();
    }

    /**
     * Get a break by ID.
     * 
     * @param id The ID of the break
     * @return The CalendarBreak if found, null otherwise
     */
    public CalendarBreak getBreakById(Long id) {
        return calendarBreakRepository.findById(id).orElse(null);
    }
}
