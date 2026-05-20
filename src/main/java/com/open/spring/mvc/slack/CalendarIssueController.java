package com.open.spring.mvc.slack;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.comment.CommentJPA;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@RestController
@RequestMapping("/api/calendar/issues")
@CrossOrigin(origins = { "http://127.0.0.1:4500", "https://pages.opencodingsociety.com" }, allowCredentials = "true")
public class CalendarIssueController {

    @Autowired
    private CalendarIssueService calendarIssueService;

    @Autowired
    private CommentJPA commentJPA;
    
    @Autowired
    private com.open.spring.mvc.groups.GroupsJpaRepository groupsJpaRepository;

    @GetMapping
        public ResponseEntity<List<Map<String, Object>>> getIssues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String dueDate,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String groupName,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LocalDate parsedDueDate = null;
        LocalDate startDate = null;
        LocalDate endDate = null;
        if (dueDate != null && !dueDate.isBlank()) {
            parsedDueDate = LocalDate.parse(dueDate);
        }
        try {
            if (start != null && !start.isBlank()) startDate = LocalDate.parse(start);
            if (end != null && !end.isBlank()) endDate = LocalDate.parse(end);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String requesterUid = userDetails.getUsername();
        boolean privileged = hasPrivilegedRole(userDetails);

        List<Map<String, Object>> data = calendarIssueService
            .getIssues(status, priority, parsedDueDate, eventId, q, author, tags, startDate, endDate, groupName, requesterUid, privileged)
            .stream()
            .map(issue -> toResponse(issue, requesterUid))
            .collect(Collectors.toList());
        return ResponseEntity.ok(data);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getIssueById(@PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));
        }

        return calendarIssueService.getIssueById(id, userDetails.getUsername(), hasPrivilegedRole(userDetails))
            .<ResponseEntity<?>>map(issue -> ResponseEntity.ok(toResponse(issue, userDetails.getUsername())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    @PostMapping
    public ResponseEntity<?> createIssue(@RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));
        }

        try {
            CalendarIssue issue = fromPayload(payload);
            CalendarIssue saved = calendarIssueService.createIssue(issue, userDetails.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved, userDetails.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to create issue", "error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateIssue(@PathVariable Long id, @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));
        }

        try {
            CalendarIssue issue = fromPayload(payload);
            return calendarIssueService.updateIssue(id, issue, userDetails.getUsername(), hasPrivilegedRole(userDetails))
                    .<ResponseEntity<?>>map(updated -> ResponseEntity.ok(toResponse(updated, userDetails.getUsername())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("message", "Issue not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to update issue", "error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));
        }

        try {
            String statusValue = payload.get("status");
            CalendarIssueStatus status = parseStatus(statusValue);
            return calendarIssueService.updateIssueStatus(id, status, userDetails.getUsername(),
                            hasPrivilegedRole(userDetails))
                    .<ResponseEntity<?>>map(updated -> ResponseEntity.ok(toResponse(updated, userDetails.getUsername())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("message", "Issue not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteIssue(@PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));
        }

        if (!calendarIssueService.deleteIssue(id, userDetails.getUsername())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Issue not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private CalendarIssue fromPayload(Map<String, Object> payload) {
        CalendarIssue issue = new CalendarIssue();
        issue.setTitle(trimToNull((String) payload.get("title")));
        issue.setDescription((String) payload.getOrDefault("description", ""));
        issue.setStatus(parseStatus((String) payload.get("status")));
        issue.setPriority(parsePriority((String) payload.get("priority")));

        String dueDate = (String) payload.get("dueDate");
        if (dueDate != null && !dueDate.isBlank()) {
            issue.setDueDate(LocalDate.parse(dueDate));
        }

        issue.setEventId(trimToNull((String) payload.get("eventId")));
        issue.setGroupName(trimToNull((String) payload.get("groupName")));
        issue.setTags(normalizeTags(payload.get("tags")));
        // assignedGroups may be passed as a JSON string or as a List; normalize to JSON string
        ObjectMapper mapper = new ObjectMapper();
        Object ag = payload.get("assignedGroups");
        if (ag != null) {
            if (ag instanceof String) {
                issue.setAssignedGroups(((String) ag).trim().isEmpty() ? null : (String) ag);
            } else {
                try {
                    issue.setAssignedGroups(mapper.writeValueAsString(ag));
                } catch (JsonProcessingException e) {
                    // fallback: store toString()
                    issue.setAssignedGroups(String.valueOf(ag));
                }
            }
        }
        return issue;
    }

    private Map<String, Object> toResponse(CalendarIssue issue, String requesterUid) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", issue.getId());
        data.put("title", issue.getTitle());
        data.put("description", issue.getDescription());
        data.put("author", issue.getOwnerUid());
        data.put("status", issue.getStatus() == null ? null : issue.getStatus().name().toLowerCase());
        data.put("priority", issue.getPriority() == null ? null : issue.getPriority().name().toLowerCase());
            // Normalize assignedGroups: return as an array (not a raw JSON string) for frontend consistency
            try {
                String agRaw = issue.getAssignedGroups();
                if (agRaw == null || agRaw.isBlank()) {
                    data.put("assignedGroups", java.util.List.of());
                    data.put("assignedGroupLabels", java.util.List.of());
                } else {
                    java.util.List<String> assigned = new java.util.ArrayList<>();
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                        Object parsed = om.readValue(agRaw, Object.class);
                        if (parsed instanceof java.util.List) {
                            for (Object o : (java.util.List<?>) parsed) assigned.add(String.valueOf(o));
                        } else if (parsed != null) assigned.add(String.valueOf(parsed));
                    } catch (Exception e) {
                        // fallback: comma-separated
                        for (String part : agRaw.split(",")) if (!part.trim().isEmpty()) assigned.add(part.trim());
                    }
                    data.put("assignedGroups", assigned);
                    // Build labels using groups repo when possible
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    for (String idOrName : assigned) {
                        String label = idOrName;
                        try {
                            Long gid = Long.valueOf(idOrName);
                            java.util.Optional<com.open.spring.mvc.groups.Groups> gopt = groupsJpaRepository.findById(gid);
                            if (gopt.isPresent()) {
                                com.open.spring.mvc.groups.Groups g = gopt.get();
                                label = ("Period " + (g.getPeriod() == null ? "" : g.getPeriod()) + " - " + (g.getName() == null ? "" : g.getName())).trim();
                            }
                        } catch (NumberFormatException nfe) {
                            java.util.Optional<com.open.spring.mvc.groups.Groups> gopt = groupsJpaRepository.findByName(idOrName);
                            if (gopt.isPresent()) {
                                com.open.spring.mvc.groups.Groups g = gopt.get();
                                label = ("Period " + (g.getPeriod() == null ? "" : g.getPeriod()) + " - " + (g.getName() == null ? "" : g.getName())).trim();
                            }
                        }
                        labels.add(label);
                    }
                    data.put("assignedGroupLabels", labels);
                }
            } catch (Exception e) {
                data.put("assignedGroups", java.util.List.of());
                data.put("assignedGroupLabels", java.util.List.of());
            }
            data.put("dueDate", issue.getDueDate() == null ? null : issue.getDueDate().toString());
        data.put("eventId", issue.getEventId());
        data.put("groupName", issue.getGroupName());
        data.put("tags", parseTags(issue.getTags()));
        data.put("createdAt", issue.getCreatedAt() == null ? null : issue.getCreatedAt().toString());
        data.put("updatedAt", issue.getUpdatedAt() == null ? null : issue.getUpdatedAt().toString());
        data.put("commentCount", issue.getId() == null ? 0L : commentJPA.countByAssignment(issueAssignmentKey(issue.getId())));
        data.put("starCount", issue.getId() == null ? 0L : commentJPA.countByAssignment(issueStarAssignmentKey(issue.getId())));
        data.put("starred", issue.getId() != null && requesterUid != null && !requesterUid.isBlank()
            && commentJPA.existsByAssignmentAndAuthor(issueStarAssignmentKey(issue.getId()), requesterUid));
        data.put("starLocked", issue.getId() != null && calendarIssueService.isIssueAssignedToUserGroups(issue, requesterUid));
        return data;
    }

    private CalendarIssueStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return CalendarIssueStatus.OPEN;
        }
        return switch (raw.trim().toLowerCase()) {
            case "open" -> CalendarIssueStatus.OPEN;
            case "in-progress", "in_progress" -> CalendarIssueStatus.IN_PROGRESS;
            case "blocked" -> CalendarIssueStatus.BLOCKED;
            case "done" -> CalendarIssueStatus.DONE;
            default -> throw new IllegalArgumentException("Invalid status: " + raw);
        };
    }

    private CalendarIssuePriority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return CalendarIssuePriority.MEDIUM;
        }
        return switch (raw.trim().toLowerCase()) {
            case "low" -> CalendarIssuePriority.LOW;
            case "medium" -> CalendarIssuePriority.MEDIUM;
            case "high" -> CalendarIssuePriority.HIGH;
            default -> throw new IllegalArgumentException("Invalid priority: " + raw);
        };
    }

    private String normalizeTags(Object rawTags) {
        if (rawTags == null) {
            return "";
        }

        if (rawTags instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
        }

        String tagsString = String.valueOf(rawTags);
        return Arrays.stream(tagsString.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String issueAssignmentKey(Long issueId) {
        return "issue-" + issueId;
    }

    private String issueStarAssignmentKey(Long issueId) {
        return issueAssignmentKey(issueId) + "::star";
    }

    private boolean hasPrivilegedRole(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_TEACHER".equals(role));
    }

    /**
     * Assign one or more groups to an issue
     * Only the issue owner or privileged users can assign groups
     * @param issueId - ID of the issue
     * @param payload - Contains groupIds array
     * @param userDetails - Authenticated user
     * @return ResponseEntity with updated issue
     */
    @PutMapping("/{issueId}/assign-groups")
    public ResponseEntity<?> assignGroups(@PathVariable Long issueId,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));
        }

        return calendarIssueService.getIssueById(issueId, userDetails.getUsername(), hasPrivilegedRole(userDetails))
                .<ResponseEntity<?>>map(issue -> {
                    @SuppressWarnings("unchecked")
                    List<String> groupIds = (List<String>) payload.get("groupIds");
                    if (groupIds == null || groupIds.isEmpty()) {
                        issue.setAssignedGroups(null);
                    } else {
                        // Store as JSON array string
                        String jsonGroups = "[" + groupIds.stream()
                                .map(id -> "\"" + id + "\"")
                                .collect(Collectors.joining(",")) + "]";
                        issue.setAssignedGroups(jsonGroups);
                    }
                    CalendarIssue updated = calendarIssueService.saveIssue(issue);
                    calendarIssueService.ensureGroupStarsForIssue(updated);
                    return ResponseEntity.ok(updated);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Issue not found")));
    }

    /**
     * Get issues assigned to any of the user's groups
     * @param userDetails - Authenticated user
     * @return ResponseEntity with list of issues
     */
    @GetMapping("/by-user-groups")
    public ResponseEntity<List<Map<String, Object>>> getIssuesByUserGroups(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // This will be implemented in CalendarIssueService with group lookup
        List<Map<String, Object>> issues = calendarIssueService.getIssuesByUserGroups(userDetails.getUsername());
        return ResponseEntity.ok(issues);
    }
}
