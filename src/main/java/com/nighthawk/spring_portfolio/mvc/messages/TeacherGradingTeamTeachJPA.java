package com.nighthawk.spring_portfolio.mvc.messages;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TeacherGradingTeamTeachJPA extends JpaRepository<TeacherGradingTeamTeach, Long> {
    
    
}