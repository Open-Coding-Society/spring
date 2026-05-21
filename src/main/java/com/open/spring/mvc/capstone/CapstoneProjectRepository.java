package com.open.spring.mvc.capstone;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CapstoneProjectRepository extends JpaRepository<CapstoneProject, Long> {
    List<CapstoneProject> findByCourseCode(String courseCode);
    boolean existsByTitle(String title);
    Optional<CapstoneProject> findByTitle(String title);
}
