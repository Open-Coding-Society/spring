package com.open.spring.mvc.groups;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.open.spring.mvc.person.Person;

public interface GroupsJpaRepository extends JpaRepository<Groups, Long> {
    
    // Find a group by its ID
    Optional<Groups> findById(Long id);
    
    // Find all groups
    List<Groups> findAll();
    List<Groups> findAllByOrderByNameAsc();

    // Eagerly load members to avoid LazyInitialization in views
    @Query("SELECT DISTINCT g FROM Groups g LEFT JOIN FETCH g.groupMembers gm ORDER BY g.id")
    List<Groups> findAllWithMembers();
    
    // Find groups containing a specific person by uid
    @Query("SELECT g FROM Groups g JOIN g.groupMembers p WHERE p.uid = :personUid")
    List<Groups> findGroupsByPersonUid(@Param("personUid") String personUid);
    
    // Find groups containing a specific person by id
    @Query("SELECT g FROM Groups g JOIN g.groupMembers p WHERE p.id = :personId")
    List<Groups> findGroupsByPersonId(@Param("personId") Long personId);

    @Query("SELECT DISTINCT g FROM Groups g LEFT JOIN FETCH g.groupMembers gm WHERE EXISTS (SELECT 1 FROM g.groupMembers p WHERE p.id = :personId) ORDER BY g.id")
    List<Groups> findGroupsByPersonIdWithMembers(@Param("personId") Long personId);

    /** Bulk course membership rows for submission-list DTO enrichment. */
    @Query("SELECT p.id, g.name FROM Groups g JOIN g.groupMembers p "
        + "WHERE p.id IN :personIds AND UPPER(g.name) IN :courseNames ORDER BY p.id, g.name")
    List<Object[]> findCourseMembershipsByPersonIds(
        @Param("personIds") Collection<Long> personIds,
        @Param("courseNames") Collection<String> courseNames);
    
    // Find groups with a specific number of members
    @Query("SELECT g FROM Groups g WHERE SIZE(g.groupMembers) = :memberCount")
    List<Groups> findGroupsByMemberCount(@Param("memberCount") int memberCount);
    
    /**
     * A group's members, for MentorRules. One query, so it works outside a transaction
     * (spring.jpa.open-in-view is false, so Groups.groupMembers can't be read lazily there).
     */
    @Query("SELECT p FROM Groups g JOIN g.groupMembers p WHERE g.id = :groupId")
    List<Person> findMembersOf(@Param("groupId") Long groupId);

    /**
     * The rows a group response lists its members from: id, uid, name, email and roles_json, the roles saying
     * whether each member is a mentor. Kept apart from findGroupMembersRaw, which other services read.
     */
    @Query(value = "SELECT p.id, p.uid, p.name, p.email, p.roles_json FROM group_members gm " +
                   "JOIN person p ON gm.person_id = p.id " +
                   "WHERE gm.group_id = :groupId " +
                   "ORDER BY p.id", nativeQuery = true)
    List<Object[]> findGroupMembersWithRolesRaw(@Param("groupId") Long groupId);

    // Get raw member data for a group (avoids Hibernate hydration issues)
    @Query(value = "SELECT p.id, p.uid, p.name, p.email FROM group_members gm " +
                   "JOIN person p ON gm.person_id = p.id " +
                   "WHERE gm.group_id = :groupId " +
                   "ORDER BY p.id", nativeQuery = true)
    List<Object[]> findGroupMembersRaw(@Param("groupId") Long groupId);

    // Find a group by exact name
    Optional<Groups> findByName(String name);

    // Whether a group's name starts with this text (group chat storage is keyed by name)
    boolean existsByNameStartingWith(String prefix);

    // Search groups by name (case-insensitive, partial match)
    @Query("SELECT g FROM Groups g WHERE LOWER(g.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY g.name")
    List<Groups> searchByName(@Param("searchTerm") String searchTerm);

    // Search groups by name with members eagerly loaded
    @Query("SELECT DISTINCT g FROM Groups g LEFT JOIN FETCH g.groupMembers WHERE LOWER(g.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY g.name")
    List<Groups> searchByNameWithMembers(@Param("searchTerm") String searchTerm);
}
