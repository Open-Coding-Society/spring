package com.open.spring.mvc.assignments;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssignmentSubmissionHistoryJPA extends JpaRepository<AssignmentSubmissionHistory, Long> {
    /** Earlier versions of one submission, most recently replaced first. */
    List<AssignmentSubmissionHistory> findBySubmissionIdOrderByIdDesc(Long submissionId);
}
