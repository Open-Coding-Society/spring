package com.open.spring.mvc.leaderboard;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "elementary_leaderboard")
public class InputScore {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String user;

    @Column(nullable = false)
    private int score;

    @Column(nullable = true)
    private String gameName;

    @Column(nullable = true)
    private String variableName;
    
    // Constructor without ID (for creating new entries)
    public InputScore(String user, int score, String gameName, String variableName) {
        this.user = user;
        this.score = score;
        this.gameName = gameName;
        this.variableName = variableName;
    }
}