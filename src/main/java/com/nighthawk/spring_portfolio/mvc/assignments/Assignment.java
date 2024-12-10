package com.nighthawk.spring_portfolio.mvc.assignments;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nighthawk.spring_portfolio.mvc.synergy.Grade;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Assignment {
    // @JsonInclude(JsonInclude.Include.NON_NULL)
    @NotNull
    @JsonPropertyOrder({"id", "name", "type", "description", "dueDate", "timestamp", "submissions"})
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique=false)
    @NotEmpty
    private String name;

    @NotEmpty
    private String type;

    private String description;

    @NotEmpty
    private String dueDate;

    @NotEmpty
    private String timestamp;

    @OneToMany(mappedBy = "assignment")
    @JsonIgnore
    private List<AssignmentSubmission> submissions;

    @OneToMany(mappedBy="assignment")
    private List<Grade> grades;

    @NotNull
    private Double points;

    @Convert(converter = AssignmentQueueConverter.class)
    private AssignmentQueue assignmentQueue;

    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void resetQueue() {
        assignmentQueue.reset();
    }

    public void initQueue(List<String> people) {
        assignmentQueue.getWorking().addAll(people);
    }

    public void addQueue(String person) {
        assignmentQueue.getWorking().remove(person);
        assignmentQueue.getWaiting().add(person);
    }

    public void removeQueue(String person) {
        assignmentQueue.getWaiting().remove(person);
        assignmentQueue.getWorking().add(person);
    }

    public void doneQueue(String person) {
        assignmentQueue.getWaiting().remove(person);
        assignmentQueue.getComplete().add(person);
    }

    public Assignment(String name, String type, String description, Double points, String dueDate) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.points = points;
        this.dueDate = dueDate; 
        this.timestamp = LocalDateTime.now().format(formatter); // fixed formatting ahhh
        // not necessary, if initialized as null converter will not insert empty queue but null, need to check and initialize in converter
        // this.assignmentQueue = new AssignmentQueue();
    }

    public static Assignment[] init() {
        return new Assignment[] {
            new Assignment("Assignment 1", "Class Homework", "Unit 1 Homework", 1.0, "10/25/2024"),
            new Assignment("Sprint 1 Live Review", "Live Review", "The final review for sprint 1", 1.0, "11/2/2024"),
        };
    }
}