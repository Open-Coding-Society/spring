package com.nighthawk.spring_portfolio.mvc.rpg.adventureAnswer;


import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.rpg.adventureChoice.AdventureChoice;
import com.nighthawk.spring_portfolio.mvc.rpg.adventureQuestion.AdventureQuestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AdventureAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Lob
    @Column(unique = false, nullable = true)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AdventureQuestion question;

    @ManyToOne
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @OneToOne
    @JoinColumn(name = "choice_id", nullable = true)
    private AdventureChoice choice;    

    @Column(unique = false, nullable = true)
    private Boolean isCorrect;

    @Column(unique = false, nullable = true)
    private Long chatScore;



    /*   
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt = new Date();
    */



    public AdventureAnswer (String content, AdventureQuestion question, Person person, AdventureChoice choice, Boolean isCorrect, Long chatScore) {
        this.content = content;
        this.question = question;
        this.person = person;
        this.choice = choice;
        this.isCorrect = isCorrect;
        this.chatScore = chatScore;
    }

}