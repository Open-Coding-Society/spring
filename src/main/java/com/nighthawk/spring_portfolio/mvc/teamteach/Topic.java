package com.nighthawk.spring_portfolio.mvc.teamteach;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topicName;
    private String date;

    @Column(columnDefinition = "TEXT") // Stores names as a comma-separated list
    private String students = "";
}
