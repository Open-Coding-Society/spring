package com.open.spring.mvc.announcement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping("/api/announcements")
public class AnnouncementControl {

    private final AnnouncementService announcementService;


    public AnnouncementControl(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    // Create Example
    @PostMapping("/create") 
    public ResponseEntity<AnnouncementEntity> createAnnouncement( @RequestParam String author, @RequestParam String title, @RequestParam String body, @RequestParam String tags) {
        AnnouncementEntity savedAnnouncement = announcementService.create(author, title, body, tags);
        return new ResponseEntity<>(savedAnnouncement, HttpStatus.CREATED);
    }

    // Read Example
    @GetMapping 
    public ResponseEntity<List<AnnouncementEntity>> getAllAnnouncements() {
        List<AnnouncementEntity> announcements = announcementService.findAll();
        return new ResponseEntity<>(announcements, HttpStatus.OK);
    }

    // Update Example
    @PostMapping("/edit/{title}")
    public ResponseEntity<AnnouncementEntity> editAnnouncement(@PathVariable String title, @RequestBody String body) {
        AnnouncementEntity announcement = announcementService.editBody(title, body);
        return new ResponseEntity<>(announcement, HttpStatus.OK);
    }

    // Delete Example
    @PostMapping("/delete/{title}")
    public ResponseEntity<HttpStatus> deleteAnnouncement(@PathVariable String title) {
        try {
            announcementService.deleteByTitle(title);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @GetMapping("/author/{author}")
    public ResponseEntity<AnnouncementEntity> getAnnouncementByAuthor(@PathVariable String author) {
        AnnouncementEntity announcement = announcementService.findByAuthor(author);
        if (announcement != null) {
            return new ResponseEntity<>(announcement, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/tags/{tags}")
    public ResponseEntity<List<AnnouncementEntity>> getAnnouncementsByTags(@PathVariable String tags) {
        List<AnnouncementEntity> announcements = announcementService.findByTags(tags);
        return new ResponseEntity<>(announcements, HttpStatus.OK);
    }

    @GetMapping("/orderedByTime")
    public ResponseEntity<List<AnnouncementEntity>> getAllAnnouncementsOrderedByTime() {
        List<AnnouncementEntity> announcements = announcementService.findAllNewestFirst();
        return new ResponseEntity<>(announcements, HttpStatus.OK);
    }
}
