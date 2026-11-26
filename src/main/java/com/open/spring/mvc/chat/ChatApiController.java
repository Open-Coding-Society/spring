package com.open.spring.mvc.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.MentorAccess;
import com.open.spring.mvc.groups.MentorRules;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatApiController {

    private final ChatService chatService;
    private final GroupsJpaRepository groupsRepository;
    private final PersonJpaRepository personRepository;
    private final MentorAccess mentorAccess;

    public ChatApiController(ChatService chatService, GroupsJpaRepository groupsRepository,
            PersonJpaRepository personRepository, MentorAccess mentorAccess) {
        this.chatService = chatService;
        this.groupsRepository = groupsRepository;
        this.personRepository = personRepository;
        this.mentorAccess = mentorAccess;
    }

    /**
     * A mentor rule's refusal for the signed-in account and this group, or null when it may go on. This older chat
     * follows the same rule as the group chat (MentorRules): only a mentor can be refused.
     */
    private ResponseEntity<Object> mentorRefusal(Groups group) {
        String uid = getCurrentUsername();
        Person person = uid == null ? null : personRepository.findByUid(uid);
        return mentorAccess.toUse(person, group).map(MentorRules.Refusal::toResponse).orElse(null);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessageRequest {
        private String content;
    }

    @GetMapping("/{groupId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getChat(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        // Only the group's members, teachers and admins read its chat.
        String currentUsername = getCurrentUsername();
        if (currentUsername == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (!isMember(groupOpt.get(), currentUsername)
                && !MentorRules.isStaff(personRepository.findByUid(currentUsername))) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        return new ResponseEntity<>(chatService.getChatHistory(groupId), HttpStatus.OK);
    }

    @PostMapping("/{groupId}")
    @Transactional
    public ResponseEntity<?> postChat(
            @PathVariable Long groupId,
            @RequestBody ChatMessageRequest request) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String currentUsername = getCurrentUsername();
        if (currentUsername == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        // A mentor hears why (MentorRules) before anyone outside the group gets a plain 403.
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }
        if (!isMember(groupOpt.get(), currentUsername)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        ChatMessage message = new ChatMessage(
                currentUsername,
                request.getContent().trim(),
                Instant.now().toEpochMilli()
        );

        List<ChatMessage> updated = chatService.addMessage(groupId, message);
        return new ResponseEntity<>(updated, HttpStatus.OK);
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }

    private boolean isMember(Groups group, String uid) {
        return group.getGroupMembers().stream()
                .anyMatch(member -> uid.equals(member.getUid()));
    }
}
