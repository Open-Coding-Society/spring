package com.nighthawk.spring_portfolio.mvc.rpg.adventureAnswer;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdventureAnswerJpaRepository extends JpaRepository<AdventureAnswer, Long> {
    List<AdventureAnswer> findByQuestionId(Long question);
    // List<Answer> findByUserId(Integer userid);
    List<AdventureAnswer> findByPersonId(Integer userid);
    List<AdventureAnswer> findByQuestionIdAndPersonId(Long questionId, Long personId);

    @Query("SELECT NEW com.nighthawk.spring_portfolio.mvc.rpg.adventureAnswer.AdventureLeaderboardDto(p.id, SUM(a.chatScore)) FROM AdventureAnswer a JOIN Person p ON a.id = p.id GROUP BY p.id ORDER BY SUM(a.chatScore) DESC")
    List<AdventureLeaderboardDto> findTop10PersonsByTotalScore();
}

