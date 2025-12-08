package com.open.spring.mvc.preferences;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/styles")
public class StylePreferenceController {

    @Autowired
    private StylePreferenceJpaRepository repository;

    @GetMapping("/")
    public ResponseEntity<List<StylePreference>> getStyles() {
        return new ResponseEntity<>(repository.findAll(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StylePreference> getStyleById(@PathVariable long id) {
        var optional = repository.findById(id);
        if (optional.isPresent()) {
            return new ResponseEntity<>(optional.get(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/person/{personId}")
    public ResponseEntity<StylePreference> getStyleByPersonId(@PathVariable long personId) {
        var optional = repository.findByPersonId(personId);
        if (optional.isPresent()) {
            return new ResponseEntity<>(optional.get(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @PostMapping("/")
    public ResponseEntity<StylePreference> createStyle(@RequestBody StylePreference newStyle) {
        return new ResponseEntity<>(repository.save(newStyle), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<StylePreference> updateStyle(@PathVariable long id, @RequestBody StylePreference updatedStyle) {
        var optional = repository.findById(id);
        if (optional.isPresent()) {
            var style = optional.get();
            style.setPersonId(updatedStyle.getPersonId());
            style.setStyleData(updatedStyle.getStyleData());
            return new ResponseEntity<>(repository.save(style), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @PutMapping("/person/{personId}")
    public ResponseEntity<StylePreference> updateStyleByPersonId(@PathVariable long personId, @RequestBody StylePreference updatedStyle) {
        var optional = repository.findByPersonId(personId);
        if (optional.isPresent()) {
            var style = optional.get();
            style.setStyleData(updatedStyle.getStyleData());
            return new ResponseEntity<>(repository.save(style), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStyle(@PathVariable long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            return new ResponseEntity<>(HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/person/{personId}")
    public ResponseEntity<Void> deleteStyleByPersonId(@PathVariable long personId) {
        var optional = repository.findByPersonId(personId);
        if (optional.isPresent()) {
            repository.deleteById(optional.get().getId());
            return new ResponseEntity<>(HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
}