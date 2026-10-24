package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for managing {@link PathwayHelpEntity} entities.
 * Provides methods to retrieve help requests by student ID and for the teacher dashboard.
 */
public interface PathwayHelpJpaRepository extends JpaRepository<PathwayHelpEntity, String> {
    /** Retrieves the most recent help request for a specific student. */
    Optional<PathwayHelpEntity> findFirstByUidOrderByCreatedAtDesc(String uid);

    /** Retrieves the 200 most recent help requests, ordered by creation time. */
    List<PathwayHelpEntity> findTop200ByOrderByCreatedAtDesc();
}
