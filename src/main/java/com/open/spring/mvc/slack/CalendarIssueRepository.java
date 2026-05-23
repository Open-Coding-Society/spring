package com.open.spring.mvc.slack;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarIssueRepository extends JpaRepository<CalendarIssue, Long> {
    List<CalendarIssue> findByDueDate(LocalDate dueDate);
    List<CalendarIssue> findByOwnerUid(String ownerUid);
    java.util.Optional<CalendarIssue> findByIdAndOwnerUid(Long id, String ownerUid);
    boolean existsByIdAndOwnerUid(Long id, String ownerUid);

    // Find issues assigned to a specific group (assigned groups stored as JSON array)
    @Query("SELECT i FROM CalendarIssue i WHERE i.assignedGroups LIKE CONCAT('%\"', :groupId, '\"%') OR i.groupName = :groupId")
    List<CalendarIssue> findByAssignedGroup(@Param("groupId") String groupId);

    // Find issues assigned to any of the provided groups
    @Query("SELECT i FROM CalendarIssue i WHERE i.assignedGroups IS NOT NULL AND i.assignedGroups != ''")
    List<CalendarIssue> findAllWithAssignedGroups();
}
