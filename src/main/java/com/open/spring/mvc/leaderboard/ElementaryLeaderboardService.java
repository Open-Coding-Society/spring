package com.open.spring.mvc.leaderboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for Elementary Leaderboard functionality
 */
@Service
public class ElementaryLeaderboardService {
    
    @Autowired
    private ElementaryLeaderboardRepository elementaryLeaderboardRepository;
    
    /**
     * Add a new score entry
     */
    public InputScore addScore(InputScore scoreEntry) {
        return elementaryLeaderboardRepository.save(scoreEntry);
    }
    
    /**
     * Delete a score entry by ID
     */
    public boolean deleteScore(Long id) {
        if (elementaryLeaderboardRepository.existsById(id)) {
            elementaryLeaderboardRepository.deleteById(id);
            return true;
        }
        return false;
    }
    
    /**
     * Get top N entries
     */
    public List<InputScore> getTopScores(int limit) {
        return elementaryLeaderboardRepository.findTopScores()
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * Get all entries ordered by score
     */
    public List<InputScore> getAllEntriesByScore() {
        return elementaryLeaderboardRepository.findAllByOrderByScoreDesc();
    }
    
    /**
     * Get entries for a specific game
     */
    public List<InputScore> getEntriesByGame(String gameName) {
        return elementaryLeaderboardRepository.findByGameNameOrderByScoreDesc(gameName);
    }
    
    /**
     * Get entries for a specific user
     */
    public List<InputScore> getUserEntries(String user) {
        return elementaryLeaderboardRepository.findByUserOrderByScoreDesc(user);
    }
    
    /**
     * Get entries for a specific user and game
     */
    public List<InputScore> getUserGameEntries(String user, String gameName) {
        return elementaryLeaderboardRepository.findByUserAndGameNameOrderByScoreDesc(user, gameName);
    }
}