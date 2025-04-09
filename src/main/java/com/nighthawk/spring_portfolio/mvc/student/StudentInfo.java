package com.nighthawk.spring_portfolio.mvc.student;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nighthawk.spring_portfolio.mvc.person.Person;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data  // Annotations to simplify writing code (ie constructors, setters)
@NoArgsConstructor
@AllArgsConstructor
@Entity // Annotation to simplify creating an entity, which is a lightweight persistence domain object. Typically, an entity represents a table in a relational database, and each entity instance corresponds to a row in that table.
@Table(name = "students" , uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class StudentInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @OneToOne
    @JoinColumn(name = "person_id") // No UNIQUE constraint added here
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnore
    private Person person;
    


    private String username;

    private int tableNumber;

    private String course;

    private ArrayList<String> tasks;
    
    private ArrayList<String> completed;

    private int trimester;

    private int period;
    
    @Column
    private String person_name;

    @Column
    private String dailyActivity;

    private Double averageRating;    

    public StudentInfo(String username, int tableNumber, String course, ArrayList<String> tasks, ArrayList<String> completed, int period, Person person) {
        this.username = username;
        this.tableNumber = tableNumber;
        this.course = course;
        this.tasks = tasks;
        this.completed = completed;
        this.period = period;
        this.person = person;
    }

    public static StudentInfo[] init(Person[] persons)
    {
        ArrayList<StudentInfo> studentInfos = new ArrayList<>();
        for(Person person: persons)
        {
            studentInfos.add(new StudentInfo(person.getUid(), 0, "CSA", new ArrayList<String>(Arrays.asList("Task 1", "Task 2")), null, 0, person));

        }
        return studentInfos.toArray(new StudentInfo[0]);
    }
    @Service
    public static class StudentService {

        @Autowired
        private StudentInfoJPARepository studentJPARepository;

        public void initialization(Person[] persons) { 
            if (studentJPARepository == null) {
                throw new RuntimeException("studentJPARepository is not initialized!");
            }
            ArrayList<StudentInfo> studentInfos = new ArrayList<>();
                for(Person person: persons) {
            studentInfos.add(new StudentInfo(person.getUid(), 0, "CSA", new ArrayList<String>(Arrays.asList("Task 1", "Task 2")), null, 0, person));
        }

            for (StudentInfo student : studentInfos) {
                Optional<StudentInfo> existingStudent = studentJPARepository.findByUsername(student.getUsername());
                if (existingStudent.isEmpty()) {
                    studentJPARepository.save(student);
                }
            }
        }

        public Iterable<StudentInfo> findAll() {
            return studentJPARepository.findAll();
        }

        public List<StudentInfo> findByUsernameCoursePeriod(String username, String course, int period) {
            return studentJPARepository.findByUsernameCoursePeriod(username, course,  period);
        }

        public StudentInfo createStudent(StudentInfo student) {
            // Check if a student with the same username already exists to avoid duplicates
            Optional<StudentInfo> existingStudent = studentJPARepository.findByUsername(student.getUsername());
            if (existingStudent.isPresent()) {
                throw new RuntimeException("A student with this username already exists.");
            }
            return studentJPARepository.save(student);
        }

        public void deleteById(Long id) {
            studentJPARepository.deleteById(id);
        }

        public Optional<StudentInfo> findByUsername(String username) {
            return studentJPARepository.findByUsername(username);
        }
        
        public List<StudentInfo> findTeam(String course, int period, int table) {
            return studentJPARepository.findTeam(course,  period, table);
        }

        public List<StudentInfo> findPeriod(String course, int period) {
            return studentJPARepository.findPeriod(course, period);
        }   
    }
}