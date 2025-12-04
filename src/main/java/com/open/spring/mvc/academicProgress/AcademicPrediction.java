package com.open.spring.mvc.academicProgress;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AcademicPrediction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String assignmentType;
    private String difficultyLevel;
    private String topic;
    private String completionStatus;
    private Double predictedScore;
    private Long createdAt;

    public AcademicPrediction(String assignmentType, String difficultyLevel, String topic, String completionStatus, Double predictedScore) {
        this.assignmentType = assignmentType;
        this.difficultyLevel = difficultyLevel;
        this.topic = topic;
        this.completionStatus = completionStatus;
        this.predictedScore = predictedScore;
        this.createdAt = System.currentTimeMillis();
    }
}
