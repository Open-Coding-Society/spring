package com.open.spring.mvc.assignments;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.open.spring.mvc.person.Person;

@Repository
public interface AssignmentJpaRepository extends JpaRepository<Assignment, Long> {
    Assignment findByName(String name);
    List<Assignment> findByAssignedGraders(Person grader);
    // hello this is a test commit

    /**
     * Assignments owned by a creator. Matches on the stable Person id rather than the
     * entity itself because Person inherits identity equality from Submitter.
     */
    @Query("SELECT DISTINCT a FROM AssignmentEntity a JOIN a.creators c WHERE c.id = :personId")
    List<Assignment> findByCreatorId(@Param("personId") Long personId);

    /** Fetch-joins creators so DTO mapping does not trigger a lazy load per assignment. */
    @Query("SELECT DISTINCT a FROM AssignmentEntity a LEFT JOIN FETCH a.creators")
    List<Assignment> findAllWithCreators();
}
