package com.nighthawk.spring_portfolio.mvc.assignments;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.nighthawk.spring_portfolio.mvc.person.Person;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AssignmentSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    @JsonBackReference
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Assignment assignment;

    @ManyToMany(cascade = jakarta.persistence.CascadeType.MERGE)
    @JoinTable(
        name = "assignment_submission_students",
        joinColumns = @JoinColumn(name = "submission_id"),
        inverseJoinColumns = @JoinColumn(name = "student_id")
    )
    private List<Person> students = new ArrayList<>();

    @ManyToMany
    @JoinTable(
        name = "assignment_submission_graders",
        joinColumns = @JoinColumn(name = "submission_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    private List<Person> assignedGraders;

    private String content;
    private Double grade;
    private String feedback;

    private String comment;

    private Long assignmentid;

    private Boolean isLate;

    @PreRemove
    private void removeStudentsFromSubmission() {
        if (students != null) {
            // before the submission is removed, remove the submission from the students' submissions list
            for (Person student : students) {
                student.getSubmissions().remove(this);
            }
        }
    }
    
    public AssignmentSubmission(Assignment assignment, List<Person> students, String content, String comment, boolean isLate) {
        this.assignment = assignment;
        this.students = students;
        this.content = content;
        this.grade = null;
        this.feedback = null;
        this.comment = comment;
        this.assignmentid=assignment.getId();
        this.isLate=isLate;

    }

    // Getter for assignment_id (foreign key column)
    public Long getAssignmentId2() {
        return assignment != null ? assignment.getId() : null;
    }    
}