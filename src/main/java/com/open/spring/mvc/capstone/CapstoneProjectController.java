package com.open.spring.mvc.capstone;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capstones")
public class CapstoneProjectController {

    @Autowired
    private CapstoneProjectRepository repository;

    @GetMapping
    public ResponseEntity<List<CapstoneProject>> getAll(
            @RequestParam(required = false) String courseCode) {
        if (courseCode != null && !courseCode.isBlank()) {
            return ResponseEntity.ok(repository.findByCourseCode(courseCode));
        }
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CapstoneProject> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CapstoneProject project) {
        if (project.getTitle() == null || project.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        project.setId(null);
        project.setCreatedAt(LocalDateTime.now());
        try {
            return new ResponseEntity<>(repository.save(project), HttpStatus.CREATED);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A project with the title \"" + project.getTitle() + "\" already exists.");
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<CapstoneProject> update(
            @PathVariable Long id, @RequestBody CapstoneProject incoming) {
        Optional<CapstoneProject> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        incoming.setId(id);
        incoming.setCreatedAt(existing.get().getCreatedAt());
        return ResponseEntity.ok(repository.save(incoming));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
