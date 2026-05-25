package com.open.spring.mvc.slack;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.comment.CommentJPA;

@Service
public class CalendarIssueService {

    @Autowired
    private CalendarIssueRepository calendarIssueRepository;

    @Autowired
    private PersonJpaRepository personJpaRepository;

    @Autowired
    private GroupsJpaRepository groupsJpaRepository;

    @Autowired
    private CommentJPA commentJPA;

    public List<CalendarIssue> getIssues(String status, String priority, LocalDate dueDate, String eventId, String q,
            String author, String tags, LocalDate start, LocalDate end, String groupName, String requesterUid, boolean privileged) {
        List<CalendarIssue> accessibleIssues = privileged
                ? calendarIssueRepository.findAll()
            : calendarIssueRepository.findAll().stream()
                .filter(issue -> canViewIssue(issue, requesterUid))
                .collect(Collectors.toList());

        return accessibleIssues.stream()
                .filter(issue -> matchesStatus(issue, status))
                .filter(issue -> matchesPriority(issue, priority))
                .filter(issue -> dueDate == null || dueDate.equals(issue.getDueDate()))
                .filter(issue -> eventId == null || eventId.isBlank() || eventId.equals(issue.getEventId()))
                .filter(issue -> matchesSearch(issue, q))
                .filter(issue -> author == null || author.isBlank() || (issue.getOwnerUid() != null && issue.getOwnerUid().equals(author)))
                .filter(issue -> tags == null || tags.isBlank() || matchesTags(issue, tags))
                .filter(issue -> groupName == null || groupName.isBlank() || (issue.getGroupName() != null && issue.getGroupName().equalsIgnoreCase(groupName)))
                .filter(issue -> inDateRange(issue, start, end))
                .sorted(Comparator.comparing(CalendarIssue::getDueDate)
                        .thenComparing(CalendarIssue::getUpdatedAt).reversed())
                .collect(Collectors.toList());
    }

    private boolean inDateRange(CalendarIssue issue, LocalDate start, LocalDate end) {
        if (start == null && end == null) return true;
        java.time.LocalDateTime createdDt = issue.getCreatedAt();
        if (createdDt == null) return false;
        LocalDate created = createdDt.toLocalDate();
        if (start != null && created.isBefore(start)) return false;
        if (end != null && created.isAfter(end)) return false;
        return true;
    }

    private boolean matchesTags(CalendarIssue issue, String rawTags) {
        if (rawTags == null || rawTags.isBlank()) return true;
        String[] parts = rawTags.split(",");
        String issueTags = issue.getTags() == null ? "" : issue.getTags().toLowerCase(Locale.ROOT);
        for (String p : parts) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && !issueTags.contains(t)) return false;
        }
        return true;
    }

    public Optional<CalendarIssue> getIssueById(Long id, String requesterUid, boolean privileged) {
        return findAccessibleIssue(id, requesterUid, privileged);
    }

    public CalendarIssue createIssue(CalendarIssue issue, String ownerUid) {
        validateIssue(issue);
        if (issue.getStatus() == null) {
            issue.setStatus(CalendarIssueStatus.OPEN);
        }
        if (issue.getPriority() == null) {
            issue.setPriority(CalendarIssuePriority.MEDIUM);
        }
        issue.setOwnerUid(ownerUid);
        CalendarIssue saved = calendarIssueRepository.save(issue);
        // After saving, create star comments for all members of assigned groups (if any)
        createGroupStarsForIssue(saved);
        return saved;
    }

    public Optional<CalendarIssue> updateIssue(Long id, CalendarIssue updated, String requesterUid, boolean privileged) {
        validateIssue(updated);

        return findAccessibleIssue(id, requesterUid, privileged).map(existing -> {
            CalendarIssueStatus nextStatus = updated.getStatus() != null ? updated.getStatus() : existing.getStatus();
            if (!isValidStatusTransition(existing.getStatus(), nextStatus)) {
                throw new IllegalArgumentException(
                        "Invalid status transition from " + existing.getStatus() + " to " + nextStatus);
            }

            existing.setTitle(updated.getTitle().trim());
            existing.setDescription(updated.getDescription());
            existing.setStatus(nextStatus);
            existing.setPriority(updated.getPriority() != null ? updated.getPriority() : existing.getPriority());
            existing.setDueDate(updated.getDueDate());
            existing.setEventId(updated.getEventId());
            existing.setGroupName(updated.getGroupName());
            existing.setAssignedGroups(updated.getAssignedGroups());
            existing.setTags(updated.getTags());
            CalendarIssue saved = calendarIssueRepository.save(existing);
            // Update group stars if assignedGroups changed
            createGroupStarsForIssue(saved);
            return saved;
        });
    }

    /**
     * For a saved issue, create star comments (assignment "issue-{id}::star") for every user
     * who is a member of any assigned group. Avoid duplicate stars.
     */
    private void createGroupStarsForIssue(CalendarIssue issue) {
        if (issue == null || issue.getId() == null) return;
        String agRaw = issue.getAssignedGroups();
        if (agRaw == null || agRaw.isBlank()) return;

        // Parse assignedGroups JSON array of ids/names
        java.util.List<String> assigned = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(agRaw, Object.class);
            if (parsed instanceof java.util.List) {
                for (Object o : (java.util.List<?>) parsed) assigned.add(String.valueOf(o));
            } else if (parsed != null) {
                assigned.add(String.valueOf(parsed));
            }
        } catch (Exception e) {
            // could be a comma-separated string — split defensively
            for (String part : agRaw.split(",")) {
                if (!part.trim().isEmpty()) assigned.add(part.trim());
            }
        }

        if (assigned.isEmpty()) return;

        // Collect unique member IDs from all groups
        java.util.Set<Long> memberIds = new java.util.HashSet<>();
        for (String gid : assigned) {
            try {
                Long groupId = Long.valueOf(gid);
                java.util.List<Object[]> rows = groupsJpaRepository.findGroupMembersRaw(groupId);
                if (rows != null) {
                    for (Object[] row : rows) {
                        if (row != null && row.length > 0 && row[0] instanceof Number) {
                            memberIds.add(((Number) row[0]).longValue());
                        }
                    }
                }
            } catch (NumberFormatException nfe) {
                // not a numeric id; try to find group by name
                java.util.Optional<Groups> gopt = groupsJpaRepository.findByName(gid);
                if (gopt.isPresent()) {
                    java.util.List<Object[]> rows = groupsJpaRepository.findGroupMembersRaw(gopt.get().getId());
                    if (rows != null) for (Object[] row : rows) if (row != null && row.length>0 && row[0] instanceof Number) memberIds.add(((Number)row[0]).longValue());
                }
            }
        }

        if (memberIds.isEmpty()) return;

        String starAssignment = "issue-" + issue.getId() + "::star";
        for (Long pid : memberIds) {
            Person p = personJpaRepository.findById(pid).orElse(null);
            if (p == null) continue;
            String uid = p.getUid();
            if (uid == null || uid.isBlank()) continue;
            // avoid duplicates
            if (commentJPA.existsByAssignmentAndAuthor(starAssignment, uid)) continue;
            commentJPA.save(new com.open.spring.mvc.comment.Comment(starAssignment, "star", uid));
        }
    }

    public Optional<CalendarIssue> updateIssueStatus(Long id, CalendarIssueStatus nextStatus,
            String requesterUid, boolean privileged) {
        return findAccessibleIssue(id, requesterUid, privileged).map(existing -> {
            if (!isValidStatusTransition(existing.getStatus(), nextStatus)) {
                throw new IllegalArgumentException(
                        "Invalid status transition from " + existing.getStatus() + " to " + nextStatus);
            }
            existing.setStatus(nextStatus);
            return calendarIssueRepository.save(existing);
        });
    }

    public boolean deleteIssue(Long id, String requesterUid) {
        if (!calendarIssueRepository.existsByIdAndOwnerUid(id, requesterUid)) {
            return false;
        }
        
        // Delete all comments associated with this issue (cascade delete with nested replies)
        String issueAssignmentKey = "issue-" + id;
        List<com.open.spring.mvc.comment.Comment> comments = commentJPA.findByAssignment(issueAssignmentKey);
        if (comments != null && !comments.isEmpty()) {
            // First delete all replies (nested comments) for each comment
            for (com.open.spring.mvc.comment.Comment comment : comments) {
                List<com.open.spring.mvc.comment.Comment> replies = commentJPA.findByParentCommentId(comment.getId());
                if (replies != null && !replies.isEmpty()) {
                    commentJPA.deleteAll(replies);
                }
            }
            // Then delete the top-level comments
            commentJPA.deleteAll(comments);
        }
        
        // Also delete star comments
        String starAssignmentKey = issueAssignmentKey + "::star";
        List<com.open.spring.mvc.comment.Comment> starComments = commentJPA.findByAssignment(starAssignmentKey);
        if (starComments != null && !starComments.isEmpty()) {
            commentJPA.deleteAll(starComments);
        }
        
        calendarIssueRepository.deleteById(id);
        return true;
    }

    private Optional<CalendarIssue> findAccessibleIssue(Long id, String requesterUid, boolean privileged) {
        if (privileged) {
            return calendarIssueRepository.findById(id);
        }
        return calendarIssueRepository.findAll().stream()
                .filter(issue -> issue.getId() != null && issue.getId().equals(id))
                .filter(issue -> canViewIssue(issue, requesterUid))
                .findFirst();
    }

    private boolean canViewIssue(CalendarIssue issue, String requesterUid) {
        if (issue == null) {
            return false;
        }

        if (requesterUid == null || requesterUid.isBlank()) {
            return false;
        }

        if (issue.getOwnerUid() == null || issue.getOwnerUid().isBlank()) {
            return true;
        }

        if (requesterUid.equals(issue.getOwnerUid())) {
            return true;
        }

        String issueGroupName = issue.getGroupName();
        if (issueGroupName == null || issueGroupName.isBlank()) {
            return true;
        }

        Person requester = personJpaRepository.findByUid(requesterUid);
        if (requester == null) {
            return false;
        }

        Set<String> requesterGroupNames = new HashSet<>();
        for (Groups group : groupsJpaRepository.findGroupsByPersonIdWithMembers(requester.getId())) {
            if (group != null && group.getName() != null) {
                requesterGroupNames.add(group.getName());
            }
        }

        return requesterGroupNames.contains(issueGroupName);
    }

    private void validateIssue(CalendarIssue issue) {
        if (issue.getTitle() == null || issue.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (issue.getDueDate() == null) {
            throw new IllegalArgumentException("Due date is required");
        }
    }

    private boolean matchesStatus(CalendarIssue issue, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return issue.getStatus() != null && issue.getStatus().name().equalsIgnoreCase(status);
    }

    private boolean matchesPriority(CalendarIssue issue, String priority) {
        if (priority == null || priority.isBlank()) {
            return true;
        }
        return issue.getPriority() != null && issue.getPriority().name().equalsIgnoreCase(priority);
    }

    private boolean matchesSearch(CalendarIssue issue, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String query = q.toLowerCase(Locale.ROOT);
        String title = issue.getTitle() == null ? "" : issue.getTitle().toLowerCase(Locale.ROOT);
        String description = issue.getDescription() == null ? "" : issue.getDescription().toLowerCase(Locale.ROOT);
        String tags = issue.getTags() == null ? "" : issue.getTags().toLowerCase(Locale.ROOT);
        return title.contains(query) || description.contains(query) || tags.contains(query);
    }

    private boolean isValidStatusTransition(CalendarIssueStatus current, CalendarIssueStatus next) {
        if (current == null || next == null || current == next) {
            return true;
        }

        return switch (current) {
            case OPEN -> next == CalendarIssueStatus.IN_PROGRESS
                    || next == CalendarIssueStatus.BLOCKED
                    || next == CalendarIssueStatus.DONE;
            case IN_PROGRESS -> next == CalendarIssueStatus.OPEN
                    || next == CalendarIssueStatus.BLOCKED
                    || next == CalendarIssueStatus.DONE;
            case BLOCKED -> next == CalendarIssueStatus.OPEN
                    || next == CalendarIssueStatus.IN_PROGRESS
                    || next == CalendarIssueStatus.DONE;
            case DONE -> next == CalendarIssueStatus.OPEN;
        };
    }

    /**
     * Save an issue (insert or update)
     * @param issue - The issue to save
     * @return The saved issue
     */
    public CalendarIssue saveIssue(CalendarIssue issue) {
        validateIssue(issue);
        return calendarIssueRepository.save(issue);
    }

    public void ensureGroupStarsForIssue(CalendarIssue issue) {
        createGroupStarsForIssue(issue);
    }

    public boolean isIssueAssignedToUserGroups(CalendarIssue issue, String requesterUid) {
        if (issue == null || requesterUid == null || requesterUid.isBlank()) {
            return false;
        }

        Person requester = personJpaRepository.findByUid(requesterUid);
        if (requester == null) {
            return false;
        }

        return isIssueAssignedToUserGroups(issue, requester);
    }

    /**
     * Get all issues assigned to any of the user's groups
     * @param userUid - User UID
     * @return List of issues assigned to user's groups
     */
    public List<Map<String, Object>> getIssuesByUserGroups(String userUid) {
        // Find the person to get their groups
        List<Person> persons = personJpaRepository.findAll();
        Person user = persons.stream()
                .filter(p -> userUid.equals(p.getUid()))
                .findFirst()
                .orElse(null);

        if (user == null) {
            return List.of();
        }

        List<Groups> userGroups = groupsJpaRepository.findGroupsByPersonIdWithMembers(user.getId());
        if (userGroups == null || userGroups.isEmpty()) {
            return List.of();
        }

        // Get all issues and filter by assigned groups
        List<CalendarIssue> allIssues = calendarIssueRepository.findAll();
        return allIssues.stream()
                .filter(issue -> isIssueAssignedToUserGroups(issue, user))
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Check if an issue is assigned to any of the user's groups
     * @param issue - The issue to check
     * @param user - The user with groups
     * @return true if issue is assigned to any user group
     */
    private boolean isIssueAssignedToUserGroups(CalendarIssue issue, Person user) {
        if (issue == null || issue.getAssignedGroups() == null || issue.getAssignedGroups().isEmpty() || user == null) {
            return false;
        }

        String assignedGroups = issue.getAssignedGroups();
        List<Groups> userGroups = groupsJpaRepository.findGroupsByPersonIdWithMembers(user.getId());
        for (Groups group : userGroups) {
            String groupId = group.getId() == null ? "" : group.getId().toString();
            String groupName = group.getName() == null ? "" : group.getName();
            String groupPeriod = group.getPeriod() == null ? "" : group.getPeriod();

            // Check if any of the group identifiers appear in the assignedGroups JSON
            if (assignedGroups.contains("\"" + groupId + "\"") ||
                assignedGroups.contains("\"" + groupName + "\"") ||
                assignedGroups.contains("\"" + groupPeriod + "\"")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> convertToMap(CalendarIssue issue) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", issue.getId());
        map.put("title", issue.getTitle());
        map.put("description", issue.getDescription());
        map.put("status", issue.getStatus());
        map.put("priority", issue.getPriority());
        map.put("dueDate", issue.getDueDate());
        map.put("eventId", issue.getEventId());
        map.put("groupName", issue.getGroupName());
        map.put("tags", issue.getTags());
        map.put("assignedGroups", issue.getAssignedGroups());
        map.put("ownerUid", issue.getOwnerUid());
        map.put("createdAt", issue.getCreatedAt());
        map.put("updatedAt", issue.getUpdatedAt());
        return map;
    }
}
