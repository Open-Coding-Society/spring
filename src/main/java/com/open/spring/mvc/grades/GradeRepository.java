package com.open.spring.mvc.grades;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {
    List<Grade> findByUid(String uid);

    List<Grade> findByAssignment(String assignment);
}
