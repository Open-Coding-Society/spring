package com.open.spring.mvc.preferences;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.NumberFormat.Style;
import java.util.List;
import java.util.Optional;


@RestController // annotation to simplify the creation of RESTful web services
@RequestMapping("/api/styles")  // all requests in file begin with this URI
public class StylePreferenceController {

    // Autowired enables Control to connect URI request and POJO Object to easily for Database CRUD operations
    @Autowired
    private StylePreferenceJpaRepository repository;

    /* GET List of ALL Styles
     * @GetMapping annotation is used for mapping HTTP GET requests onto specific handler methods.
     * Endpoint: GET /api/styles/
     */
    @GetMapping("/")
    public ResponseEntity<List<StylePreference>> getStyles() {
        // ResponseEntity returns List of Styles provided by JPA findAll()
        return new ResponseEntity<>(repository.findAll(), HttpStatus.OK);
    }

    /* GET Single Style by ID
     * Endpoint: GET /api/styles/{id}
     * @PathVariable extracts the {id} from the URI
     */
    @GetMapping("/{id}")
    public ResponseEntity<StylePreference> getStyleById(@PathVariable long id) {
        Optional<StylePreference> optional = repository.findById(id);
        if (optional.isPresent()) {
            StylePreference style = optional.get();
            return new ResponseEntity<>(style, HttpStatus.OK);
        }
        // Style not found
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /* POST Create New Style
     * Endpoint: POST /api/styles/
     * @RequestBody receives the new style data as JSON
     */
    @PostMapping("/")
    public ResponseEntity<StylePreference> createStyle(@RequestBody StylePreference newStyle) {
        // Save the new style to database
        StylePreference savedStyle = repository.save(newStyle);
        // Return the saved style with generated ID and CREATED status
        return new ResponseEntity<>(savedStyle, HttpStatus.CREATED);
    }

    /* PUT Update Existing Style
     * Endpoint: PUT /api/styles/{id}
     * @PathVariable extracts the {id} from URI
     * @RequestBody receives the updated style data as JSON
     */
    @PutMapping("/{id}")
    public ResponseEntity<StylePreference> updateStyle(@PathVariable long id, @RequestBody StylePreference updatedStyle) {
        Optional<StylePreference> optional = repository.findById(id);
        if (optional.isPresent()) {
            StylePreference style = optional.get();
            
            // Update the fields with new values
            style.setName(updatedStyle.getName());
            style.setSassCode(updatedStyle.getSassCode());
            style.setDescription(updatedStyle.getDescription());
            
            // Save updated style to database
            repository.save(style);
            return new ResponseEntity<>(style, HttpStatus.OK);
        }
        // Style not found
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /* DELETE Remove Style
     * Endpoint: DELETE /api/styles/{id}
     * @PathVariable extracts the {id} from URI
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStyle(@PathVariable long id) {
        Optional<StylePreference> optional = repository.findById(id);
        if (optional.isPresent()) {
            // Delete the style from database
            repository.deleteById(id);
            return new ResponseEntity<>(HttpStatus.OK);
        }
        // Style not found
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
}