package com.open.spring.mvc.generic;

import com.open.spring.mvc.identity.User;
import com.open.spring.mvc.identity.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/events")
public class GenericEventController {

    @Autowired
    private AlgorithmicEventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/{algoName}")
    public ResponseEntity<AlgorithmicEvent> logEvent(
            @PathVariable String algoName,
            @RequestBody AlgorithmicEvent event,
            @AuthenticationPrincipal UserDetails userDetails) {

        // Events can be anonymous or authenticated
        if (userDetails != null) {
            String email = userDetails.getUsername();
            Optional<User> user = userRepository.findByEmail(email);
            user.ifPresent(event::setUser);
        }

        event.setAlgoName(algoName);
        AlgorithmicEvent saved = eventRepository.save(event);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @GetMapping("/{algoName}")
    public ResponseEntity<List<AlgorithmicEvent>> listEvents(@PathVariable String algoName) {
        // In a real app, this should probably be restricted or paginated
        return new ResponseEntity<>(eventRepository.findByAlgoName(algoName), HttpStatus.OK);
    }
}
