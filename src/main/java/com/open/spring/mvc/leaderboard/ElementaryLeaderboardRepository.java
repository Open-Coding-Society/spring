package com.open.spring.mvc.leaderboard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for Elementary Leaderboard table (uses InputScore entity)
 */
@Repository
public interface ElementaryLeaderboardRepository extends JpaRepository<InputScore, Long> {
    
    // Get all scores ordered by score descending
    @Query("SELECT s FROM InputScore s ORDER BY s.score DESC")
    List<InputScore> findAllByOrderByScoreDesc();
    
    // Get top N scores
    @Query("SELECT s FROM InputScore s ORDER BY s.score DESC")
    List<InputScore> findTopScores();
    
    // Get scores for a specific game
    @Query("SELECT s FROM InputScore s WHERE s.gameName = :gameName ORDER BY s.score DESC")
    List<InputScore> findByGameNameOrderByScoreDesc(@Param("gameName") String gameName);
    
    // Get scores for a specific user
    @Query("SELECT s FROM InputScore s WHERE s.user = :user ORDER BY s.score DESC")
    List<InputScore> findByUserOrderByScoreDesc(@Param("user") String user);
    
    // Get scores for a specific user and game
    @Query("SELECT s FROM InputScore s WHERE s.user = :user AND s.gameName = :gameName ORDER BY s.score DESC")
    List<InputScore> findByUserAndGameNameOrderByScoreDesc(@Param("user") String user, @Param("gameName") String gameName);
}