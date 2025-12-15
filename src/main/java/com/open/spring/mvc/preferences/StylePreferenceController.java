package com.open.spring.mvc.preferences;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import java.util.Map;

@RestController
@RequestMapping("/api/styles")
public class StylePreferenceController {

    @Autowired
    private PersonJpaRepository personRepository;

    @GetMapping("/person/{personId}")
    public ResponseEntity<Map<String, Object>> getStyleByPersonId(@PathVariable long personId) {
        var optional = personRepository.findById(personId);
        if (optional.isPresent()) {
            return new ResponseEntity<>(optional.get().getStyleData(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @PostMapping("/person/{personId}")
    public ResponseEntity<Map<String, Object>> createOrUpdateStyle(@PathVariable long personId, @RequestBody Map<String, Object> styleData) {
        var optional = personRepository.findById(personId);
        if (optional.isPresent()) {
            Person person = optional.get();
            person.setStyleData(styleData);
            personRepository.save(person);
            return new ResponseEntity<>(person.getStyleData(), HttpStatus.CREATED);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @PutMapping("/person/{personId}")
    public ResponseEntity<Map<String, Object>> updateStyleByPersonId(@PathVariable long personId, @RequestBody Map<String, Object> styleData) {
        var optional = personRepository.findById(personId);
        if (optional.isPresent()) {
            Person person = optional.get();
            person.setStyleData(styleData);
            personRepository.save(person);
            return new ResponseEntity<>(person.getStyleData(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/person/{personId}")
    public ResponseEntity<Void> deleteStyleByPersonId(@PathVariable long personId) {
        var optional = personRepository.findById(personId);
        if (optional.isPresent()) {
            Person person = optional.get();
            person.setStyleData(new java.util.HashMap<>());
            personRepository.save(person);
            return new ResponseEntity<>(HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }}