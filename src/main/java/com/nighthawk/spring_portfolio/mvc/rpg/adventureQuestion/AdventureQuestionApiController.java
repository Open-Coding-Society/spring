// define package and import necessary libraries
package com.nighthawk.spring_portfolio.mvc.rpg.adventureQuestion;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.rpg.adventureAnswer.AdventureAnswer;
import com.nighthawk.spring_portfolio.mvc.rpg.adventureAnswer.AdventureAnswerJpaRepository;
import com.nighthawk.spring_portfolio.mvc.rpg.gamifyGame.GameJpaRepository;

import lombok.Data;



// define rest controller and set base endpoint mapping
@RestController
@RequestMapping("/question")
public class AdventureQuestionApiController {

    @Autowired
    private GameJpaRepository gameJpaRepository;
    @Autowired
    private PersonJpaRepository personJpaRepository;
    @Autowired
    private AdventureAnswerJpaRepository answerJpaRepository;
    @Autowired
    private AdventureQuestionJpaRepository questionJpaRepository;

    @Data
    public static class GameStatsDTO {
        private String uid;  
        private String gname;     
        private Map<String, Object> stats; 
    }

    @GetMapping("transitionToWallstreet/{personid}")
    public ResponseEntity<Integer> transitionToWallstreet(@PathVariable Integer personid) {
        List<AdventureAnswer> useranswers = answerJpaRepository.findByPersonId(personid);

        // count the total answers
        Integer questionsAnswered = useranswers.size();

        // return the count with an ok status
        return new ResponseEntity<>(questionsAnswered, HttpStatus.OK);
    }

    @GetMapping("transitionToParadise/{personid}")
    public ResponseEntity<Boolean> transitionToParadise(@PathVariable Long personid) {
        List<AdventureQuestion> meteorQuestions = questionJpaRepository.findByCategory("Meteor");
    
        if (!meteorQuestions.isEmpty()) {
            for (AdventureQuestion meteorQuestion : meteorQuestions) {
                List<AdventureAnswer> answers = answerJpaRepository.findByQuestionIdAndPersonId(meteorQuestion.getId(), personid);
    
                boolean isCorrect = answers.stream().anyMatch(ans -> Boolean.TRUE.equals(ans.getIsCorrect()));
                if (!isCorrect) {
                    return ResponseEntity.ok(false);
                }
            }
            return ResponseEntity.ok(true);
        }
    
        return ResponseEntity.ok(false);
    }
    
    
}