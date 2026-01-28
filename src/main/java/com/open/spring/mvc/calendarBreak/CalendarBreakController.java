package com.open.spring.mvc.calendarBreak;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for managing calendar breaks.
 * Handles creation, deletion, and retrieval of breaks through REST API endpoints.
 */
@RestController
@RequestMapping("/api/calendar/breaks")
public class CalendarBreakController {

    @Autowired
    private CalendarBreakService calendarBreakService;

    /**
     * Create a new break for a specific date.
     * When a break is created, all events for that date are moved to the next non-break day.
     * 
     * @param payload JSON payload containing date, name, description, and moveToNextNonBreakDay
     * @return ResponseEntity with the created CalendarBreak or error message
     */
    @PostMapping("/create")
    public ResponseEntity<?> createBreak(@RequestBody Map<String, Object> payload) {
        try {
            String dateStr = (String) payload.get("date");
            String name = (String) payload.get("name");
            String description = (String) payload.get("description");
            Boolean moveToNextNonBreakDay = (Boolean) payload.getOrDefault("moveToNextNonBreakDay", true);

            // Validate date
            if (dateStr == null || dateStr.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Date is required"));
            }

            // Validate name
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Break name is required"));
            }

            LocalDate date = LocalDate.parse(dateStr);
            
            // Handle null description
            if (description == null) {
                description = "";
            }

            CalendarBreak breakRecord = calendarBreakService.createBreak(date, name, description, moveToNextNonBreakDay);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Break created successfully",
                "break", breakRecord
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid date format. Use YYYY-MM-DD"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to create break: " + e.getMessage()
            ));
        }
    }

    /**
     * Edit a break's name and description.
     * 
     * @param id The ID of the break to edit
     * @param payload JSON payload containing name and description
     * @return ResponseEntity with the updated break or error message
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> editBreak(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        try {
            String name = (String) payload.get("name");
            String description = (String) payload.get("description");

            // Validate name if provided
            if (name != null && name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Break name cannot be empty"));
            }

            CalendarBreak updatedBreak = calendarBreakService.updateBreak(id, name, description);
            
            if (updatedBreak == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Break updated successfully",
                "break", updatedBreak
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to update break: " + e.getMessage()
            ));
        }
    }

    /**
     * Delete a break by its ID.
     * 
     * @param id The ID of the break to delete
     * @return ResponseEntity with success or error message
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBreak(@PathVariable Long id) {
        try {
            boolean deleted = calendarBreakService.deleteBreakById(id);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Break deleted successfully"
                ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to delete break: " + e.getMessage()
            ));
        }
    }

    /**
     * Get all breaks.
     * 
     * @return ResponseEntity with list of all breaks
     */
    @GetMapping("/")
    public ResponseEntity<?> getAllBreaks() {
        try {
            List<CalendarBreak> breaks = calendarBreakService.getAllBreaks();
            return ResponseEntity.ok(breaks);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve breaks: " + e.getMessage()
            ));
        }
    }

    /**
     * Get breaks for a specific date.
     * 
     * @param date The date to check (format: YYYY-MM-DD)
     * @return ResponseEntity with list of breaks for that date
     */
    @GetMapping("/by-date")
    public ResponseEntity<?> getBreaksByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            List<CalendarBreak> breaks = calendarBreakService.getBreaksByDate(date);
            return ResponseEntity.ok(breaks);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve breaks: " + e.getMessage()
            ));
        }
    }

    /**
     * Check if a date is a break day.
     * 
     * @param date The date to check (format: YYYY-MM-DD)
     * @return ResponseEntity with boolean indicating if it's a break day
     */
    @GetMapping("/is-break-day")
    public ResponseEntity<?> isBreakDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            boolean isBreak = calendarBreakService.isBreakDay(date);
            return ResponseEntity.ok(Map.of("isBreakDay", isBreak));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to check break day: " + e.getMessage()
            ));
        }
    }

    /**
     * Get a break by its ID.
     * 
     * @param id The ID of the break
     * @return ResponseEntity with the break or error message
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getBreakById(@PathVariable Long id) {
        try {
            CalendarBreak breakRecord = calendarBreakService.getBreakById(id);
            if (breakRecord != null) {
                return ResponseEntity.ok(breakRecord);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve break: " + e.getMessage()
            ));
        }
    }
}
