package com.open.spring.mvc.grades;

import jakarta.persistence.*;

@Entity
@Table(name = "grades")
public class Grade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String uid;

    @Column(nullable = false)
    private String assignment;

    @Column(nullable = false)
    private Double score;

    @Column(name = "class")
    @com.fasterxml.jackson.annotation.JsonProperty("class")
    private String course;

    // @Column(nullable = false)
    // private String gradeLevel;

    public Grade() {
    }

    public Grade(String uid, String assignment, Double score, String course) {
        this.uid = uid;
        this.assignment = assignment;
        this.score = score;
        this.course = course;
        // this.gradeLevel = gradeLevel;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getAssignment() {
        return assignment;
    }

    public void setAssignment(String assignment) {
        this.assignment = assignment;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getCourse() {
        return course;
    }

    public void setCourse(String course) {
        this.course = course;
    }

    // public String getGradeLevel() { return gradeLevel; }
    // public void setGradeLevel(String gradeLevel) { this.gradeLevel = gradeLevel;
    // }

}
