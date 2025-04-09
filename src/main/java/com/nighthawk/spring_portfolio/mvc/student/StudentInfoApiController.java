package com.nighthawk.spring_portfolio.mvc.student;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@RestController
@RequestMapping("/api/students")
public class StudentInfoApiController {

    private final Dotenv dotenv = Dotenv.load();
    
    @Autowired
    private StudentInfoJPARepository studentJPARepository;

    @GetMapping("/all")
    public ResponseEntity<Iterable<StudentInfo>> getAllStudents() {
        return ResponseEntity.ok(studentJPARepository.findAll());
    }

    @Getter
    public static class CriteriaDto {
        private String username;
        private String course;
        private int period; 
    }

    @PostMapping("/find")
    public ResponseEntity<StudentInfo> getStudentByCriteria(
            @RequestBody CriteriaDto criteriaDto) {
        
        List<StudentInfo> students = studentJPARepository.findByUsernameCoursePeriod(criteriaDto.getUsername(), criteriaDto.getCourse(), criteriaDto.getPeriod());
        
        if (students.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(students.get(0));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<StudentInfo> createStudent(@RequestBody StudentInfo student) {
        try {
            Optional<StudentInfo> existingStudents = studentJPARepository.findByUsername(student.getUsername());
            if (!existingStudents.isEmpty()) {
                throw new RuntimeException("A student with this GitHub ID already exists.");
            }
            StudentInfo createdStudent = studentJPARepository.save(student);
            return ResponseEntity.ok(createdStudent);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteStudentByUsername(@RequestParam String username) {
        Optional<StudentInfo> student = studentJPARepository.findByUsername(username);
        
        if (student.isPresent()) {
            studentJPARepository.deleteById(student.get().getId());  // Delete student by ID
            return ResponseEntity.ok("Student with username '" + username + "' has been deleted.");
        } else {
            return ResponseEntity.status(404).body("Student with username '" + username + "' not found.");
        }
    }

    @GetMapping("/apiKey")
    public ResponseEntity<String> getApiKey() {
        String apiKey = dotenv.get("GITHUB_TOKEN");
        return ResponseEntity.ok(apiKey);
    }

    @Getter
    public static class TeamDto {
        private String course;
        private int period; 
        private int table;
    }

    @PostMapping("/find-team")
    public ResponseEntity<Iterable<StudentInfo>> getTeamByCriteria(
            @RequestBody TeamDto teamDto) {
        
        List<StudentInfo> students = studentJPARepository.findTeam(teamDto.getCourse(), teamDto.getPeriod(), teamDto.getTable());
        
        if (students.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(students);
        }
    }

    @Getter
    public static class StudentName {
        private String name;
    }

    @Getter
    @AllArgsConstructor
    public static class StudentInfoDto {
        private String username;
        private String course;
        private int tableNumber;
        private int period;
    }

    @PostMapping("/findbyName")
    public ResponseEntity<List<StudentInfoDto>> getStudentByName(@RequestBody StudentName studentName) {
        List<Object[]> studentData = studentJPARepository.findSelectedFieldsByPersonName(studentName.getName());

        if (studentData.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            List<StudentInfoDto> studentDtos = studentData.stream()
                .map(data -> new StudentInfoDto(
                    (String) data[0],  // username
                    (String) data[1],  // course
                    (Integer) data[2], // tableNumber
                    (Integer) data[3]  // period
                ))
                .toList();
            
            return ResponseEntity.ok(studentDtos);
        }
    }



    @Getter 
    public static class StudentDto {
        private String username;
        private ArrayList<String> tasks;
    }


    @PostMapping("/update-tasks")
    public ResponseEntity<StudentInfo> updateTasks(@RequestBody StudentDto studentDto) {
        String username =  studentDto.getUsername();
        ArrayList<String> tasks = studentDto.getTasks();


        Optional<StudentInfo> student = studentJPARepository.findByUsername(username);

        if (student.isPresent()) {
            StudentInfo student1 = student.get();
            ArrayList<String> newTasks = student1.getTasks();
            
            for (String task: tasks) {
                newTasks.add(task);
            }
            
            student1.setTasks(newTasks);
            studentJPARepository.save(student1);
            return ResponseEntity.ok(student1);
        }

        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
    @Getter
    public static class TasksDto {
        private String username;
        private String task;
    }

    @PostMapping("/complete-task")
    public ResponseEntity<String> completeTask(@RequestBody TasksDto tasksDto) {
        Optional<StudentInfo> optionalStudent = studentJPARepository.findByUsername(tasksDto.getUsername());
        String task = tasksDto.getTask();

        if (optionalStudent.isPresent()) {
            StudentInfo student = optionalStudent.get();
            if (student.getCompleted() == null) {
                student.setCompleted(new ArrayList<>()); 
            }

            if (student.getTasks().contains(task)) {
                student.getTasks().remove(task);
                student.getCompleted().add(task + " - Completed");
                studentJPARepository.save(student);
                return ResponseEntity.ok("Task marked as completed.");
            } else {
                return ResponseEntity.badRequest().body("Task not found in the student's task list.");
            }
        } else {
            return ResponseEntity.status(404).body("Student not found.");
        }
    }

    @Getter 
    public static class PeriodDto {
        private String course;
        private int period;
    }

    @PostMapping("/find-period")
    public ResponseEntity<Iterable<StudentInfo>> getPeriodByTrimester(
        @RequestBody PeriodDto periodDto) {
            
        List<StudentInfo> students = studentJPARepository.findPeriod(periodDto.getCourse(), periodDto.getPeriod());

        if (students.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(students);
        }
    }
    @Getter
        public static class ProgressDto {
        private int table;
        private int period; // The table number to calculate progress for
    }

    @PostMapping("/progress")
    public ResponseEntity<Integer> getProgress(@RequestBody ProgressDto progressDto) {
        System.out.println(progressDto);
        int table = progressDto.getTable();
        int period = progressDto.getPeriod();
        List<StudentInfo> allStudents = studentJPARepository.findAll();
        List<StudentInfo> studentsAtTable = new ArrayList<>();
        System.out.println("Received table number: " + progressDto.getTable());
        System.out.println("Received period: " + progressDto.getPeriod());

        for (StudentInfo student : allStudents) {
            if (student.getTableNumber() == table && student.getPeriod() == period) {
                studentsAtTable.add(student);
            }
        }
        if (studentsAtTable.isEmpty()) {
            System.out.println("No students found for table " + table);
            return ResponseEntity.status(404).body(0); 
        }
        int totalPossibleTasks = 0;
        int totalCompletedTasks = 0;

        for (StudentInfo student : studentsAtTable) {
            if (student.getTasks() != null) {
                totalPossibleTasks += student.getTasks().size(); // Count all pending tasks for this student
            }
            if (student.getCompleted() != null) {
                totalCompletedTasks += student.getCompleted().size(); // Count all completed tasks for this student
            }
        }

        // Step 5: Handle edge case where no tasks exist at all
        if (totalPossibleTasks == 0 && totalCompletedTasks == 0) {
            return ResponseEntity.ok(0); // No tasks exist, so progress is 0%
        }

        int totalTasks = totalPossibleTasks + totalCompletedTasks;
        int progress = (int) Math.round(((double) totalCompletedTasks / totalTasks) * 100);
        return ResponseEntity.ok(progress);
    }
    @Getter
    @Setter
    public static class DailyActivityDto {
        private String username;
        private String dailyActivity;
    }
    @PostMapping("/save-daily-activity")
    public ResponseEntity<String> saveDailyActivity(@RequestBody DailyActivityDto dailyActivityDto) {
        Optional<StudentInfo> optionalStudent = studentJPARepository.findByUsername(dailyActivityDto.getUsername());

        if (optionalStudent.isPresent()) {
            StudentInfo student = optionalStudent.get();
            student.setDailyActivity(dailyActivityDto.getDailyActivity());
            studentJPARepository.save(student);
            return ResponseEntity.ok("Daily activity saved successfully.");
        } else {
            return ResponseEntity.status(404).body("Student not found.");
        }
    }

    @Getter
    @Setter
    public static class RatingDto {
        private String username; // Username of the student being rated
        private int communication; // Rating for communication (1-5)
        private int teamwork; // Rating for teamwork (1-5)
        private int problemSolving; // Rating for problem-solving (1-5)
        private int creativity; // Rating for creativity (1-5)
        private int punctuality; // Rating for punctuality (1-5)
    }
    @PostMapping("/rate")
    public ResponseEntity<String> rateStudent(@RequestBody RatingDto ratingDto) {
        // Validate the ratings (ensure they are between 1 and 5)
        if (!isValidRating(ratingDto.getCommunication()) ||
            !isValidRating(ratingDto.getTeamwork()) ||
            !isValidRating(ratingDto.getProblemSolving()) ||
            !isValidRating(ratingDto.getCreativity()) ||
            !isValidRating(ratingDto.getPunctuality())) {
            return ResponseEntity.badRequest().body("All ratings must be between 1 and 5.");
        }

        // Find the student by username
        Optional<StudentInfo> optionalStudent = studentJPARepository.findByUsername(ratingDto.getUsername());
        if (!optionalStudent.isPresent()) {
            return ResponseEntity.status(404).body("Student with username '" + ratingDto.getUsername() + "' not found.");
        }

        // Calculate the average rating
        double averageRating = calculateAverageRating(ratingDto);

        // Update the student's rating (assuming the StudentInfo entity has a field for averageRating)
        StudentInfo student = optionalStudent.get();
        student.setAverageRating(averageRating); // Add this field to the StudentInfo entity if it doesn't exist
        studentJPARepository.save(student);

        return ResponseEntity.ok("Student rated successfully. Average rating: " + averageRating);
    }

    // Helper method to validate ratings
    private boolean isValidRating(int rating) {
        return rating >= 1 && rating <= 5;
    }

    // Helper method to calculate the average rating
    private double calculateAverageRating(RatingDto ratingDto) {
        int sum = ratingDto.getCommunication() +
                  ratingDto.getTeamwork() +
                  ratingDto.getProblemSolving() +
                  ratingDto.getCreativity() +
                  ratingDto.getPunctuality();
        return sum / 5.0; // Divide by 5 to get the average
    }
}