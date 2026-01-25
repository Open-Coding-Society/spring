package com.open.spring.mvc.itempriority;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.Getter;
import lombok.Setter;

/**
 * REST Controller for managing user item priorities (P0-P3).
 * Stores priorities directly in Person.itemPriorities JSON field.
 * 
 * Endpoints:
 * - GET  /api/user/item-priorities         - Get all priorities (optional ?path= filter)
 * - PUT  /api/user/item-priorities         - Set single item priority
 * - POST /api/user/item-priorities/bulk    - Bulk update priorities
 * - DELETE /api/user/item-priorities       - Delete/reset a priority
 */
@RestController
@RequestMapping("/api/user/item-priorities")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class ItemPriorityController {

    private static final Set<String> VALID_PRIORITIES = Set.of("P0", "P1", "P2", "P3");

    @Autowired
    private PersonJpaRepository personRepository;

    // ==================== DTOs ====================

    @Getter
    @Setter
    public static class SetPriorityRequest {
        private String itemUrl;
        private String priority;
        private String uid;  // User identifier
    }

    @Getter
    @Setter
    public static class BulkPriorityRequest {
        private String uid;  // User identifier
        private Map<String, String> priorities;  // itemUrl -> priority
    }

    @Getter
    @Setter
    public static class DeletePriorityRequest {
        private String itemUrl;
        private String uid;  // User identifier
    }

    // ==================== Endpoints ====================

    /**
     * GET /api/user/item-priorities
     * Retrieve all item priorities for a user.
     * 
     * @param uid User identifier (required)
     * @param path Optional path prefix filter (e.g., "/csp/sprint1/")
     * @return Map of itemUrl -> priority
     */
    @GetMapping
    public ResponseEntity<?> getPriorities(
            @RequestParam String uid,
            @RequestParam(required = false) String path) {
        
        Map<String, Object> response = new HashMap<>();

        // Find the user
        Person person = personRepository.findByUid(uid);
        if (person == null) {
            response.put("success", false);
            response.put("error", "User not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        // Get priorities from Person's itemPriorities field
        Map<String, String> priorities = person.getItemPriorities();
        if (priorities == null) {
            priorities = new HashMap<>();
        }

        // Filter by path prefix if provided
        Map<String, String> data;
        if (path != null && !path.isEmpty()) {
            final String pathPrefix = path;
            data = priorities.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(pathPrefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else {
            data = new HashMap<>(priorities);
        }

        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/user/item-priorities
     * Set priority for a single item.
     * 
     * @param request Contains itemUrl, priority, and uid
     * @return Success response with updated data
     */
    @PutMapping
    @Transactional
    public ResponseEntity<?> setPriority(@RequestBody SetPriorityRequest request) {
        Map<String, Object> response = new HashMap<>();

        // Validate request
        if (request.getUid() == null || request.getUid().isEmpty()) {
            response.put("success", false);
            response.put("error", "User ID (uid) is required");
            return ResponseEntity.badRequest().body(response);
        }

        if (request.getItemUrl() == null || request.getItemUrl().isEmpty()) {
            response.put("success", false);
            response.put("error", "Item URL is required");
            return ResponseEntity.badRequest().body(response);
        }

        if (!VALID_PRIORITIES.contains(request.getPriority())) {
            response.put("success", false);
            response.put("error", "Invalid priority. Must be P0, P1, P2, or P3");
            return ResponseEntity.badRequest().body(response);
        }

        // Find the user
        Person person = personRepository.findByUid(request.getUid());
        if (person == null) {
            response.put("success", false);
            response.put("error", "User not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        // Update the priority in Person's itemPriorities map
        Map<String, String> priorities = person.getItemPriorities();
        if (priorities == null) {
            priorities = new HashMap<>();
        }
        priorities.put(request.getItemUrl(), request.getPriority());
        person.setItemPriorities(priorities);
        
        personRepository.save(person);

        response.put("success", true);
        response.put("message", "Priority updated");
        response.put("data", Map.of(
                "itemUrl", request.getItemUrl(),
                "priority", request.getPriority()
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/user/item-priorities/bulk
     * Update multiple item priorities at once.
     * 
     * @param request Contains uid and priorities map
     * @return Success response with count of updated items
     */
    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<?> bulkUpdate(@RequestBody BulkPriorityRequest request) {
        Map<String, Object> response = new HashMap<>();

        // Validate request
        if (request.getUid() == null || request.getUid().isEmpty()) {
            response.put("success", false);
            response.put("error", "User ID (uid) is required");
            return ResponseEntity.badRequest().body(response);
        }

        if (request.getPriorities() == null || request.getPriorities().isEmpty()) {
            response.put("success", false);
            response.put("error", "Priorities map is required");
            return ResponseEntity.badRequest().body(response);
        }

        // Find the user
        Person person = personRepository.findByUid(request.getUid());
        if (person == null) {
            response.put("success", false);
            response.put("error", "User not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        // Get existing priorities
        Map<String, String> priorities = person.getItemPriorities();
        if (priorities == null) {
            priorities = new HashMap<>();
        }

        int count = 0;
        for (Map.Entry<String, String> entry : request.getPriorities().entrySet()) {
            String itemUrl = entry.getKey();
            String priorityValue = entry.getValue();

            // Skip invalid priorities
            if (!VALID_PRIORITIES.contains(priorityValue)) {
                continue;
            }

            priorities.put(itemUrl, priorityValue);
            count++;
        }

        person.setItemPriorities(priorities);
        personRepository.save(person);

        response.put("success", true);
        response.put("message", "Updated " + count + " priorities");
        response.put("count", count);

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/user/item-priorities
     * Delete/reset a priority for a specific item.
     * 
     * @param request Contains itemUrl and uid
     * @return Success response
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<?> deletePriority(@RequestBody DeletePriorityRequest request) {
        Map<String, Object> response = new HashMap<>();

        // Validate request
        if (request.getUid() == null || request.getUid().isEmpty()) {
            response.put("success", false);
            response.put("error", "User ID (uid) is required");
            return ResponseEntity.badRequest().body(response);
        }

        if (request.getItemUrl() == null || request.getItemUrl().isEmpty()) {
            response.put("success", false);
            response.put("error", "Item URL is required");
            return ResponseEntity.badRequest().body(response);
        }

        // Find the user
        Person person = personRepository.findByUid(request.getUid());
        if (person == null) {
            response.put("success", false);
            response.put("error", "User not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        // Remove the priority from Person's itemPriorities map
        Map<String, String> priorities = person.getItemPriorities();
        if (priorities != null) {
            priorities.remove(request.getItemUrl());
            person.setItemPriorities(priorities);
            personRepository.save(person);
        }

        response.put("success", true);
        response.put("message", "Priority reset to default");

        return ResponseEntity.ok(response);
    }
}
