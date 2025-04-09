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
            // GitHub Command Quiz
            {"1", "capital goods.", "false"},
            {"1", "scarcity.", "false"},
            {"1", "opportunity cost.", "true"},
            {"1", "noneconomic wants.", "false"},

            {"2", "manipulate financial data to indicate profitability.", "false"},
            {"2", "ensure that it is following government regulations.", "true"},
            {"2", "develop efficient production processes.", "false"},
            {"2", "evaluate its vendors' performance.", "false"},

            {"3", "Ease of transitions", "false"},
            {"3", "Decreased cost", "true"},
            {"3", "Increased efficiency  ", "false"},
            {"3", "Risk of increased fraud", "false"},

            {"4", "To automate financial decision-making.", "true"},
            {"4", "To manage physical office supplies.", "false"},
            {"4", "To handle customer complaints manually.", "false"},
            {"4", "To replace all employees.", "false"},

            {"5", "Git.", "true"},
            {"5", "Excel.", "false"},
            {"5", "Slack.", "false"},
            {"5", "Zoom.", "false"},

            {"6", "Phishing.", "true"},
            {"6", "Cloud backup.", "false"},
            {"6", "Two-factor authentication.", "false"},
            {"6", "VPN usage.", "false"},

            {"7", "Allows access from multiple locations and devices.", "true"},
            {"7", "Eliminates the need for internet.", "false"},
            {"7", "Requires local installation of all software.", "false"},
            {"7", "Only works on weekends.", "false"},

            {"8", "To identify and fix software bugs.", "true"},
            {"8", "To redesign UI only.", "false"},
            {"8", "To launch advertisements.", "false"},
            {"8", "To reset passwords.", "false"},

            {"9", "It protects data from unauthorized access.", "true"},
            {"9", "It compresses image files.", "false"},
            {"9", "It blocks websites.", "false"},
            {"9", "It stores files temporarily.", "false"},

            {"10", "Collaboration and adaptability in development.", "true"},
            {"10", "Avoiding feedback loops.", "false"},
            {"10", "Heavy reliance on documentation only.", "false"},
            {"10", "Complete design before any coding.", "false"},

            {"11", "Enables software to communicate with other software.", "true"},
            {"11", "Increases screen resolution.", "false"},
            {"11", "Secures Wi-Fi networks.", "false"},
            {"11", "Manages employee schedules.", "false"},

            {"12", "It protects digital assets from attacks.", "true"},
            {"12", "It slows down network speed.", "false"},
            {"12", "It creates physical backups.", "false"},
            {"12", "It tracks time usage.", "false"},
        
            {"13", "Interest earned only on principal", "false"},
            {"13", "Interest earned on both principal and previously earned interest", "true"},
            {"13", "A tax deduction", "false"},
            {"13", "Simple interest", "false"},

          
            {"14", "Bonds", "false"},
            {"14", "Savings accounts", "false"},
            {"14", "Index funds", "false"},
            {"14", "Individual stocks", "true"},

       
            {"15", "Putting all your money in one stock", "false"},
            {"15", "Investing in crypto only", "false"},
            {"15", "Spreading investments across different assets", "true"},
            {"15", "Selling all investments before a crash", "false"},

           
            {"16", "A market where prices are rising", "false"},
            {"16", "A market where prices are stable", "false"},
            {"16", "A market where prices are falling", "true"},
            {"16", "A market with rapid trades", "false"},

        
            {"17", "Electronic Transfer Fund", "false"},
            {"17", "Exchange-Traded Fund", "true"},
            {"17", "Equity Tax Fund", "false"},
            {"17", "Emergency Treasury Fund", "false"},

    
            {"18", "IRA", "false"},
            {"18", "403b", "false"},
            {"18", "401k", "true"},
            {"18", "HSA", "false"},

            
            {"19", "principal", "true"},
            {"19", "dividend", "false"},
            {"19", "yield", "false"},
            {"19", "revenue", "false"},

       
            {"20", "Stocks", "false"},
            {"20", "Bond", "true"},
            {"20", "Mutual funds", "false"},
            {"20", "Real estate", "false"},

            
            {"21", "Bear", "false"},
            {"21", "Bull", "true"},
            {"21", "Correction", "false"},
            {"21", "Crash", "false"},

            
            {"22", "HSA", "false"},
            {"22", "IRA", "true"},
            {"22", "ETF", "false"},
            {"22", "GDP", "false"},

        };

    }
    
}