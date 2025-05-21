package com.nighthawk.spring_portfolio.mvc.rpg.adventureQuestion;



import com.nighthawk.spring_portfolio.mvc.rpg.adventureRubric.AdventureRubric;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AdventureQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique = false, nullable = true)
    private String title;

    @Column(unique = false, nullable = false)
    private String content;

    @Column(unique = false, nullable = false)
    private String category;

    @Column(nullable = false)
    private int points;
    
    @ManyToOne
    @JoinColumn(name = "rubric_id", nullable = true)
    private AdventureRubric rubric;
    
    public AdventureQuestion(String title, String content, String category, int points) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.points = points;
  
    }

    public AdventureQuestion(String title, String content, String category, int points, AdventureRubric rubric) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.points = points;
        this.rubric = rubric;
    }

    public static AdventureQuestion createQuestion(String title, String content, int points) {
        AdventureQuestion question = new AdventureQuestion();
        question.setTitle(title);
        question.setContent(content);
        question.setPoints(points);

        return question;
    }

    public static String[][] init() {
        // Create SynergyGrade objects
        return new String[][] {
            {"Fidelity Quiz", "What is the purpose of Artificial Intelligence in FinTech?", "Fidelity", "10000", "null"},
            {"Fidelity Quiz", "Which tool is essential for version control in software projects?", "Fidelity", "10000", "null"},
            {"Fidelity Quiz", "Which of the following is a common cybersecurity threat?", "Fidelity", "10000", "null"},
            {"Tech Quiz", "What is a benefit of using cloud computing in business?", "Schwab", "10000", "null"},
            {"Tech Quiz", "Why do developers use debugging tools?", "Schwab", "10000", "null"},
            {"Tech Quiz", "What does encryption do?", "Schwab", "10000", "null"},
        };
    }
}
