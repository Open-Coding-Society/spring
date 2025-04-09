package com.nighthawk.spring_portfolio.mvc.synergy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import com.nighthawk.spring_portfolio.mvc.assignments.Assignment;
import com.nighthawk.spring_portfolio.mvc.assignments.AssignmentJpaRepository;
import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

import lombok.Getter;

@Controller
@RequestMapping("/mvc/synergy")
public class SynergyViewController {
    @Autowired
    private SynergyGradeJpaRepository gradeRepository;
    
    @Autowired
    private SynergyGradeRequestJpaRepository gradeRequestRepository;

    @Autowired
    private AssignmentJpaRepository assignmentRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Getter
    public static class SynergyGradeRequestDto {
        private String assignmentName;
        private String explanation;
        private Double gradeSuggestion;
        private String graderName;
        private String studentName;
        private Long id;
        
        public SynergyGradeRequestDto(SynergyGradeRequest request) {
            this.assignmentName = request.getAssignment().getName();
            this.explanation = request.getExplanation();
            this.gradeSuggestion = request.getGradeSuggestion();
            this.graderName = request.getGrader().getName();
            this.studentName = request.getStudent().getName();
            this.id = request.getId();
        }
    }

    /**
     * Opens the teacher or student gradebook. The teacher gradebook is for editing grades, while the student gradebook allows them to view grades.
     * @param model The parameters for the webpage
     * @param userDetails The details of the logged in user
     * @return The template for the gradebook
     */
    @GetMapping("/gradebook")
    public String editGrades(Model model, @AuthenticationPrincipal UserDetails userDetails) throws ResponseStatusException {
        // Load the user
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        if (user == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged in user to view this"
            );
        }

        // Load the assignments
        List<Assignment> assignments = assignmentRepository.findAll();

        // If the user is a student, allow them to view their grades, else allow them to edit grades
        if (user.hasRoleWithName("ROLE_STUDENT")) {
            List<SynergyGrade> studentGrades = gradeRepository.findByStudent(user);
        
            Map<Long, SynergyGrade> assignmentGrades = new HashMap<>();
            for (SynergyGrade grade : studentGrades) {
                assignmentGrades.put(grade.getAssignment().getId(), grade);
            }
        
            model.addAttribute("assignments", assignments);
            model.addAttribute("assignmentGrades", assignmentGrades);
            return "synergy/view_student_grades";
        } else if (user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN")) {
            // Load info from db, for now we'll show everyone but ideally it should only show students in future
            // List<Person> students = personRepository.findPeopleWithRole("ROLE_STUDENT");
            List<Person> students = personRepository.findAll();
            List<SynergyGrade> gradesList = gradeRepository.findAll();
            List<SynergyGradeRequest> gradeRequests = gradeRequestRepository.findAll();

            // Preprocess grades into a map so that they can be easily accessed on the frontend
            Map<Long, Map<Long, Double>> grades = createGradesMap(gradesList, assignments, students);
            
            // Preprocess pending requests into a map so that they can be easily accessed on the frontend
            Map<String, List<SynergyGradeRequestDto>> pendingRequestsMap = new HashMap<>();
            for (SynergyGradeRequest request : gradeRequests) {
                if (request.getStatus() == 0) {  // Only include pending requests
                    String key = request.getAssignment().getId().toString() + "-" + request.getStudent().getId().toString();
                    pendingRequestsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(new SynergyGradeRequestDto(request));
                }
            }
            
            // Pass in information to thymeleaf template
            model.addAttribute("assignments", assignments);
            model.addAttribute("students", students);
            model.addAttribute("grades", grades);
            model.addAttribute("pendingRequestsMap", pendingRequestsMap);
            model.addAttribute("gradeRequests", gradeRequests);

            return "synergy/edit_grades";
        }

        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "You must a student, teacher, or admin to view grades."
        );
    }

    /**
     * Formats the grades, students, and assignments for displaying for teachers
     * @param gradesList A list of grades
     * @param assignments A list of assignments
     * @param students A list of students
     * @return A map of format Map[ASSIGNMENT_ID: Map[STUDENT_ID: Grade]]
     */
    private Map<Long, Map<Long, Double>> createGradesMap(List<SynergyGrade> gradesList, List<Assignment> assignments, List<Person> students) {
        Map<Long, Map<Long, Double>> gradesMap = new HashMap<>();

        // Default values
        for (Assignment assignment : assignments) {
            gradesMap.put(assignment.getId(), new HashMap<>());
            for (Person student : students) {
                gradesMap.get(assignment.getId()).put(student.getId(), null);
            }
        }

        // Create the map
        for (SynergyGrade grade : gradesList) {
            if (gradesMap.containsKey(grade.getAssignment().getId())) {
                gradesMap.get(grade.getAssignment().getId()).put(grade.getStudent().getId(), grade.getGrade());
            }
        }

        return gradesMap;
    }

    /**
     * A page to view grade requests.
     * @param model The parameters for the webpage
     * @return The template for viewing grade requests
     */
    @GetMapping("/view-grade-requests")
    public String viewRequests(Model model) {
        List<SynergyGradeRequest> requests = gradeRequestRepository.findAll();
        model.addAttribute("requests", requests);
        return "synergy/view_grade_requests";
    }

    /**
     * A page to create grade requests.
     * @param model The parameters for the webpage
     * @return The template for create grade requests
     */
    @GetMapping("/create-grade-request")
    public String createGradeRequest(Model model) {
        List<Assignment> assignments = assignmentRepository.findAll();
        List<Person> students = personRepository.findPeopleWithRole("ROLE_STUDENT");

        model.addAttribute("assignments", assignments);
        model.addAttribute("students", students);

        return "synergy/create_grade_request";
    }
}
