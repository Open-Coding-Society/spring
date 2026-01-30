package com.open.spring.mvc.leaderboard;

import com.open.spring.mvc.leaderboard.InputScore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;

/**
 * API Controller for Elementary Leaderboard
 * CORS configured for public access without authentication
 * Security is handled in SecurityConfig.java
 * 
 * Endpoints:
 * - GET /api/elementary-leaderboard - Get all elementary scores
 * - POST /api/elementary-leaderboard - Add new elementary score
 * - DELETE /api/elementary-leaderboard/{id} - Delete score by ID
 * - GET /api/elementary-leaderboard/top/{limit} - Top N scores
 * - GET /api/elementary-leaderboard/game/{gameName} - Scores for specific game
 * - GET /api/elementary-leaderboard/user/{user} - Scores for specific user
 */
@RestController
@RequestMapping("/api/elementary-leaderboard")
@CrossOrigin(
    origins = "*",
    allowedHeaders = "*",
    methods = {
        org.springframework.web.bind.annotation.RequestMethod.GET,
        org.springframework.web.bind.annotation.RequestMethod.POST,
        org.springframework.web.bind.annotation.RequestMethod.DELETE,
        org.springframework.web.bind.annotation.RequestMethod.OPTIONS
    },
    allowCredentials = "false"
)
public class ElementaryLeaderboardController {
    
    @Autowired
    private ElementaryLeaderboardService elementaryLeaderboardService;
    
    /**
     * READ - Get all elementary leaderboard entries ordered by score
     * GET /api/elementary-leaderboard
     */
    @GetMapping("")
    public ResponseEntity<List<InputScore>> getAllEntries() {
        try {
            List<InputScore> entries = elementaryLeaderboardService.getAllEntriesByScore();
            // Always return a valid JSON array, even if empty
            if (entries == null) {
                entries = List.of();
            }
            System.out.println("Elementary Leaderboard: Returning " + entries.size() + " entries");
            return ResponseEntity.ok(entries);
        } catch (Exception e) {
            System.err.println("Error fetching elementary leaderboard: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok(List.of()); // Return empty array on error
        }
    }
    
    /**
     * CREATE - Add new elementary score entry
     * POST /api/elementary-leaderboard
     * Request body: { "user": "playerName", "score": 100, "gameName": "GameName" }
     */
    @PostMapping("")
    public ResponseEntity<?> addScore(@RequestBody InputScore scoreEntry) {
        try {
            // Validate input
            if (scoreEntry.getScore() < 0) {
                return ResponseEntity.badRequest().body("Score must be non-negative");
            }
            
            // Set default game name if not provided
            if (scoreEntry.getGameName() == null || scoreEntry.getGameName().trim().isEmpty()) {
                scoreEntry.setGameName("Global");
            }
            
            // Force id to null to ensure new entry is created (not updated)
            scoreEntry.setId(null);
            
            // Save the score
            InputScore savedEntry = elementaryLeaderboardService.addScore(scoreEntry);
            
            System.out.println("Elementary Leaderboard: Added new score - ID: " + savedEntry.getId() + 
                ", User: " + scoreEntry.getUser() + ", Score: " + scoreEntry.getScore() + 
                ", Game: " + scoreEntry.getGameName());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(savedEntry);
        } catch (Exception e) {
            System.err.println("Error adding elementary score: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to add score: " + e.getMessage());
        }
    }
    
    /**
     * DELETE - Delete score entry by ID
     * DELETE /api/elementary-leaderboard/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteScore(@PathVariable Long id) {
        try {
            boolean deleted = elementaryLeaderboardService.deleteScore(id);
            
            if (deleted) {
                System.out.println("Elementary Leaderboard: Deleted score with ID: " + id);
                return ResponseEntity.ok().body("Score deleted successfully");
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Score with ID " + id + " not found");
            }
        } catch (Exception e) {
            System.err.println("Error deleting elementary score: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to delete score: " + e.getMessage());
        }
    }
    
    /**
     * READ - Get top N scores
     * GET /api/elementary-leaderboard/top/{limit}
     */
    @GetMapping("/top/{limit}")
    public ResponseEntity<List<InputScore>> getTopScores(@PathVariable int limit) {
        List<InputScore> entries = elementaryLeaderboardService.getTopScores(limit);
        return ResponseEntity.ok(entries != null ? entries : List.of());
    }
    
    /**
     * READ - Get leaderboard entries for a specific game
     * GET /api/elementary-leaderboard/game/{gameName}
     */
    @GetMapping("/game/{gameName}")
    public ResponseEntity<List<InputScore>> getEntriesByGame(@PathVariable String gameName) {
        List<InputScore> entries = elementaryLeaderboardService.getEntriesByGame(gameName);
        return ResponseEntity.ok(entries != null ? entries : List.of());
    }
    
    /**
     * READ - Get leaderboard entries for a specific user
     * GET /api/elementary-leaderboard/user/{user}
     */
    @GetMapping("/user/{user}")
    public ResponseEntity<List<InputScore>> getUserEntries(@PathVariable String user) {
        List<InputScore> entries = elementaryLeaderboardService.getUserEntries(user);
        return ResponseEntity.ok(entries != null ? entries : List.of());
    }
    
    /**
     * READ - Get entries for a specific user and game
     * GET /api/elementary-leaderboard/user/{user}/game/{gameName}
     */
    @GetMapping("/user/{user}/game/{gameName}")
    public ResponseEntity<List<InputScore>> getUserGameEntries(
            @PathVariable String user, 
            @PathVariable String gameName) {
        List<InputScore> entries = elementaryLeaderboardService.getUserGameEntries(user, gameName);
        return ResponseEntity.ok(entries != null ? entries : List.of());
    }
}