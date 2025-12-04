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
public class AcademicProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studentId;
    private String assignmentId;
    private Integer score;
    private Integer maxScore;
    private String submissionDate;
    private String dueDate;
    private String completionStatus;
    private String assignmentType;
    private String difficultyLevel;
    private String topic;

    public AcademicProgress(Long studentId, String assignmentId, Integer score, String topic) {
        this.studentId = studentId;
        this.assignmentId = assignmentId;
        this.score = score;
        this.topic = topic;
    }
}
