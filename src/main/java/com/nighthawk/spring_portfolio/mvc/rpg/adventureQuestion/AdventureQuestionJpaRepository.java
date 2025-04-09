package com.nighthawk.spring_portfolio.mvc.rpg.adventureQuestion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdventureQuestionJpaRepository extends JpaRepository<AdventureQuestion, Long> {
    AdventureQuestion findByTitle(String title); 
    AdventureQuestion findByContent(String content); 
    AdventureQuestion findById(Integer questionid);
    Optional<AdventureQuestion> findFirstByCategory(String category);
    List<AdventureQuestion> findByCategory(String category);
    List<AdventureQuestion> findAllByOrderByTitleAsc();
}

