package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

import lombok.Getter;

@RestController
@RequestMapping("/api/tinkle")
public class TinkleApiController {

    @Autowired
    private TinkleJPARepository repository;

    @Autowired
    private PersonJpaRepository personRepository;


    @Getter
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
}