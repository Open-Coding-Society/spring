package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for managing {@link CsPathwayHelpRequest} entities.
 * Provides methods to retrieve help requests by student ID and for the teacher dashboard.
 */
public interface CsPathwayHelpRequestRepository extends JpaRepository<CsPathwayHelpRequest, String> {
    /** Retrieves the most recent help request for a specific student. */
    Optional<CsPathwayHelpRequest> findFirstByUidOrderByCreatedAtDesc(String uid);

    /** Retrieves the 200 most recent help requests, ordered by creation time. */
    List<CsPathwayHelpRequest> findTop200ByOrderByCreatedAtDesc();
}
