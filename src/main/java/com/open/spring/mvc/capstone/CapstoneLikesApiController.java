package com.open.spring.mvc.capstone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * REST API controller for capstone project likes.
 */
@RestController
@RequestMapping("/api/capstone/likes")
public class CapstoneLikesApiController {

    @Autowired
    private CapstoneLikeService capstoneLikeService;

    @Autowired
    private PersonJpaRepository personRepository;

    @GetMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> getLikes(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("projectId") String projectId) {
        if (userDetails == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not authenticated"),
                    HttpStatus.UNAUTHORIZED);
        }

        Person person = personRepository.findByUid(userDetails.getUsername());
        if (person == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not found"),
                    HttpStatus.NOT_FOUND);
        }

        CapstoneLikeResponse response = capstoneLikeService.getLikeStatus(projectId, person);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> likeProject(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("projectId") String projectId) {
        if (userDetails == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not authenticated"),
                    HttpStatus.UNAUTHORIZED);
        }

        Person person = personRepository.findByUid(userDetails.getUsername());
        if (person == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not found"),
                    HttpStatus.NOT_FOUND);
        }

        CapstoneLikeResponse response = capstoneLikeService.likeProject(projectId, person);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @DeleteMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> unlikeProject(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("projectId") String projectId) {
        if (userDetails == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not authenticated"),
                    HttpStatus.UNAUTHORIZED);
        }

        Person person = personRepository.findByUid(userDetails.getUsername());
        if (person == null) {
            return new ResponseEntity<>(
                    java.util.Map.of("error", "User not found"),
                    HttpStatus.NOT_FOUND);
        }

        CapstoneLikeResponse response = capstoneLikeService.unlikeProject(projectId, person);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
