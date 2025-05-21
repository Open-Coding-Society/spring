package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;

@RestController
@RequestMapping("/api/tinkle")
public class TinkleApiController {

    @Autowired
    private TinkleJPARepository repository;

    @Autowired
    private PersonJpaRepository personRepository;


    @Getter
    @Setter
    public static class TinkleDto {
        private String studentEmail;
        private String timeIn;
        // private double averageDuration;
    }

    //POST request that adds the student's time entry into the datatable.
    @PostMapping("/add")
    public ResponseEntity<Object> timeInOut(@RequestBody TinkleDto tinkleDto) {
        //First finds the student by name
        Optional<Tinkle> student = repository.findByPersonName(tinkleDto.getStudentEmail());
        //If the student exists then it adds the timeIn to the student's timeIn column
        if (student.isPresent()) {
            student.get().addTimeIn(tinkleDto.getTimeIn());
            repository.save(student.get());
            return new ResponseEntity<>(student.get(), HttpStatus.OK);
        } else {
            return new ResponseEntity<>("Student not found", HttpStatus.NOT_FOUND);
        }
    }

    //GET Request to get all of the tinkle objects
    @GetMapping("/all")
    public List<Tinkle> getAll() {
        return repository.findAll();
    }

    //GET REQUEst by the person's name, used to find a person's specific bathrooms statistics on the bathroom frontend
    @GetMapping("/{name}")
    public ResponseEntity<Object> getTinkle(@PathVariable String name) {
        //JPA function to find the person
        Optional<Tinkle> tinkle = repository.findByPersonName(name);
        if (tinkle.isPresent()) {
            Tinkle tinklePerson = tinkle.get();
            return new ResponseEntity<>(tinklePerson, HttpStatus.OK);
        } else {
            return new ResponseEntity<>("Student not found", HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/repopulate")
    public ResponseEntity<Object> populatePeople() {
        var personArray = personRepository.findAllByOrderByNameAsc();

        for(Person person: personArray) {
            Tinkle tinkle = new Tinkle(person,"");
            Optional<Tinkle> tinkleFound = repository.findByPersonName(tinkle.getPersonName());
            if(tinkleFound.isEmpty()) {
                repository.save(tinkle);
            }
        }

        return ResponseEntity.ok("Complete");
    }

    @GetMapping("/timeIn/{studentName}")
    public ResponseEntity<Object> getTimeIn(@PathVariable String studentName) {
        System.out.println("🔍 Fetching timeIn for: " + studentName);
    
        // Retrieve stored timeIn from memory (ApprovalRequestApiController)
        String timeIn = ApprovalRequestApiController.getTimeInFromMemory(studentName);

        if (timeIn != null) {
            System.out.println("Retrieved timeIn from memory for " + studentName + ": " + timeIn);
            return ResponseEntity.ok(timeIn); // Return timeIn value
        } else {
            System.out.println("Student not found in memory: " + studentName);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Student not found");
        }
    }

    @DeleteMapping("/bulk/clear")
    public ResponseEntity<?> clearTable(HttpServletRequest request) {
        // Check for admin authentication
        String role = (String) request.getAttribute("role");
        if (role == null || !role.equals("ADMIN")) {
            return new ResponseEntity<>("Unauthorized - Admin access required", HttpStatus.UNAUTHORIZED);
        }

        try {
            // Delete all records
            repository.deleteAll();
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "All bathroom records have been cleared");
            
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Failed to clear table: " + e.getMessage());
            
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/bulk/extract")
    public ResponseEntity<List<TinkleDto>> bulkExtract() {
        // Fetch all Tinkle entries from the database
        List<Tinkle> tinkleList = repository.findAll();
        
        // Map Tinkle entities to TinkleDto objects
        List<TinkleDto> tinkleDtos = new ArrayList<>();
        for (Tinkle tinkle : tinkleList) {
            TinkleDto tinkleDto = new TinkleDto();
            tinkleDto.setStudentEmail(tinkle.getPersonName());
            tinkleDto.setTimeIn(tinkle.getTimeIn());
            // You can add more fields here if needed
            tinkleDtos.add(tinkleDto);
        }
        
        // Return the list of TinkleDto objects
        return new ResponseEntity<>(tinkleDtos, HttpStatus.OK);
    }

    @PostMapping("/bulk/create")
    public ResponseEntity<Object> bulkCreateTinkles(@RequestBody List<TinkleDto> tinkleDtos) {
        List<String> createdTinkles = new ArrayList<>();
        List<String> duplicateTinkles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (TinkleDto tinkleDto : tinkleDtos) {
            try {
                // Check if student already exists
                Optional<Tinkle> existingTinkle = repository.findByPersonName(tinkleDto.getStudentEmail());
                
                if (existingTinkle.isPresent()) {
                    // Update existing tinkle with new timeIn data
                    Tinkle tinkle = existingTinkle.get();
                    tinkle.addTimeIn(tinkleDto.getTimeIn());
                    repository.save(tinkle);
                    createdTinkles.add(tinkleDto.getStudentEmail() + " (updated)");
                } else {
                    // Find the person first
                    Person person = personRepository.findByName(tinkleDto.getStudentEmail());
                    
                    if (person != null) {
                        // Create new tinkle entry
                        Tinkle newTinkle = new Tinkle(person, tinkleDto.getTimeIn());
                        repository.save(newTinkle);
                        createdTinkles.add(tinkleDto.getStudentEmail());
                    } else {
                        errors.add("Person not found with name: " + tinkleDto.getStudentEmail());
                    }
                }
            } catch (Exception e) {
                errors.add("Exception occurred for student: " + tinkleDto.getStudentEmail() + " - " + e.getMessage());
            }
        }

        // Prepare the response
        Map<String, Object> response = new HashMap<>();
        response.put("created", createdTinkles);
        response.put("duplicates", duplicateTinkles);
        response.put("errors", errors);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}