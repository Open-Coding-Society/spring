package com.nighthawk.spring_portfolio.mvc.rpg.adventureChoice;

import com.nighthawk.spring_portfolio.mvc.rpg.adventureQuestion.AdventureQuestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AdventureChoice {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AdventureQuestion question;

    @Column(unique = false, nullable = false)
    private String choice;

    @Column(nullable = false)
    private Boolean is_correct;    

    public AdventureChoice (AdventureQuestion question, String choice, Boolean is_correct) {
        this.question = question;
        this.choice = choice;
        this.is_correct = is_correct;
    }    
    
    public static String[][] init() {
        return new String[][] {
            {"1", "To automate financial decision-making.", "true"},
            {"1", "To manage physical office supplies.", "false"},
            {"1", "To handle customer complaints manually.", "false"},
            {"1", "To replace all employees.", "false"},

            {"2", "Git.", "true"},
            {"2", "Excel.", "false"},
            {"2", "Slack.", "false"},
            {"2", "Zoom.", "false"},

            {"3", "Phishing.", "true"},
            {"3", "Cloud backup.", "false"},
            {"3", "Two-factor authentication.", "false"},
            {"3", "VPN usage.", "false"},

            {"4", "Allows access from multiple locations and devices.", "true"},
            {"4", "Eliminates the need for internet.", "false"},
            {"4", "Requires local installation of all software.", "false"},
            {"4", "Only works on weekends.", "false"},

            {"5", "To identify and fix software bugs.", "true"},
            {"5", "To redesign UI only.", "false"},
            {"5", "To launch advertisements.", "false"},
            {"5", "To reset passwords.", "false"},

            {"6", "It protects data from unauthorized access.", "true"},
            {"6", "It compresses image files.", "false"},
            {"6", "It blocks websites.", "false"},
            {"6", "It stores files temporarily.", "false"},
        };

    }
    
}