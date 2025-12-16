package com.open.spring.mvc.preferences;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@RestController
@RequestMapping("/api/styles")
public class StylePreferenceController {

    @Autowired
    private PersonJpaRepository personRepository;

    @GetMapping("/person/{uid}")
    public ResponseEntity<Map<String, Object>> getStyleByUid(@PathVariable String uid) {
        Person person = personRepository.findByUid(uid);
        if (person != null) {
            return new ResponseEntity<>(person.getStyleData(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    @PostMapping("/person/{uid}")
    public ResponseEntity<Map<String, Object>> createOrUpdateStyleByUid(
            @PathVariable String uid,
            @RequestBody Map<String, Object> styleData) {

        Person person = personRepository.findByUid(uid);
        if (person != null) {
            person.setStyleData(styleData);
            personRepository.save(person);
            return new ResponseEntity<>(person.getStyleData(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    @PutMapping("/person/{uid}")
    public ResponseEntity<Map<String, Object>> updateStyleByUid(
            @PathVariable String uid,
            @RequestBody Map<String, Object> styleData) {

        Person person = personRepository.findByUid(uid);
        if (person != null) {
            person.setStyleData(styleData);
            personRepository.save(person);
            return new ResponseEntity<>(person.getStyleData(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/person/{uid}")
    public ResponseEntity<Void> deleteStyleByUid(@PathVariable String uid) {
        Person person = personRepository.findByUid(uid);
        if (person != null) {
            person.setStyleData(new HashMap<>());
            personRepository.save(person);
            return new ResponseEntity<>(HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
}