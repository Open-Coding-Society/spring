package com.open.spring.mvc.rpg.adventureChoice;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.rpg.adventureQuestion.AdventureQuestion;
public interface AdventureChoiceJpaRepository extends JpaRepository<AdventureChoice, Long> {
    AdventureChoice findById(Integer choiceid);
    AdventureChoice findByChoice(String choice);
    AdventureChoice findByQuestionAndChoice(AdventureQuestion question, String choice);
    List<AdventureChoice> findByQuestionId(Integer questionid);
    List<AdventureChoice> findByQuestionId(long  questionid);
}