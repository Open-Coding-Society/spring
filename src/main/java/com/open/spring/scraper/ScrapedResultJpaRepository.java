package com.open.spring.scraper;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScrapedResultJpaRepository extends JpaRepository<ScrapedResult, Long> {
    List<ScrapedResult> findByPersonIdOrderByCreatedAtDesc(Long personId);

    /** For the teacher/admin dashboard: every student's saved runs, not just one. */
    List<ScrapedResult> findAllByOrderByCreatedAtDesc();
}
