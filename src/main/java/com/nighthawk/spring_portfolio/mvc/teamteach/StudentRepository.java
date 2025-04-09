package com.nighthawk.spring_portfolio.mvc.teamteach;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentRepository extends JpaRepository<Student, Long> {
}
