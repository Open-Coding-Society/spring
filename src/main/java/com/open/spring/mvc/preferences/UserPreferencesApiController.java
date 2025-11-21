package com.open.spring.mvc.userpreference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;


@RestController // annotation to simplify the creation of RESTful web services
@RequestMapping("/api/styles")  // all requests in file begin with this URI
public class StylesPreference {

    // Autowired enables Control to connect URI request and POJO Object to easily for Database CRUD operations
    @Autowired
    private StylePreferenceJpaRepository repository;

    /* GET List of Styles
     * @GetMapping annotation is used for mapping HTTP GET requests onto specific handler methods.
     */
    @GetMapping("/")
    public ResponseEntity<List<Styles>> getStylePreference() {
        // ResponseEntity returns List of Jokes provide by JPA findAll()
        return new ResponseEntity<>( repository.findAll(), HttpStatus.OK);
    }

    /* Update Like
     * @PutMapping annotation is used for mapping HTTP PUT requests onto specific handler methods.
     * @PathVariable annotation extracts the templated part {id}, from the URI
     */
    @PostMapping("/styles/{id}")
    public ResponseEntity<Styles> setStyle(@PathVariable long id) {
        /* 
        * Optional (below) is a container object which helps determine if a result is present. 
        * If a value is present, isPresent() will return true
        * get() will return the value.
        */
        Optional<Styles> optional = repository.findById(id);
        if (optional.isPresent()) {  // Good ID
            Styles style = optional.get();  // value from findByID
            Style.setStyle(style.getStyle()+1); // increment value
            repository.save(style);  // save entity
            return new ResponseEntity<>(style, HttpStatus.OK);  // OK HTTP response: status code, headers, and body
        }
        // Bad ID
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);  // Failed HTTP response: status code, headers, and body
    }
}
