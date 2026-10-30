package com.open.spring.mvc.assignments;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubmissionGradeEventJpaRepository extends JpaRepository<SubmissionGradeEventEntity, Long> {
    List<SubmissionGradeEventEntity> findBySubmissionIdOrderByIdAsc(Long submissionId);
}
