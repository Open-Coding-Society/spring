package com.open.spring.mvc.academicProgress;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcademicProgressRepository extends JpaRepository<AcademicProgress, Long> {
    // This interface is intentionally left blank. 
    // Default JPA methods are used for database operations.
}
