package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository; 

public interface TinkleJPARepository extends JpaRepository<Tinkle, Long> {
    // Optional<Tinkle> findByStudentEmail(String studentEmail);'
    Optional<Tinkle> findByPersonName(String personName);
    
    @Override
    List<Tinkle> findAll();
}
