package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for managing {@link PathwayEntityReport} entities.
 * Handles pairing code lookups and retrieval of reported setup reports.
 */
public interface PathwayJpsRepositorySetup extends JpaRepository<PathwayEntityReport, String> {
    /** Finds a setup report associated with a specific pairing code. */
    Optional<PathwayEntityReport> findByPairingCode(String pairingCode);

    /** Checks if a pairing code is currently in use. */
    boolean existsByPairingCode(String pairingCode);

    /** Retrieves all setup reports that have been successfully uploaded. */
    List<PathwayEntityReport> findAllByReportedAtIsNotNull();
}
