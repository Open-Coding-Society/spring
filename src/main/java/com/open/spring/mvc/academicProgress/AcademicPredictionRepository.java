package com.open.spring.mvc.academicProgress;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcademicPredictionRepository extends JpaRepository<AcademicPrediction, Long> {
}
