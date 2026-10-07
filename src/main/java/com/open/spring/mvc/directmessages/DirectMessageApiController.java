package com.open.spring.mvc.directmessages;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * REST API for direct messages between 2 or more people. Every endpoint below
 * resolves "who am I" from the JWT-authenticated principal (never a
 * client-supplied name), and every conversation-scoped endpoint checks that
 * the caller is actually a participant before returning anything -- that
 * check is the whole point of this being separate from Groups' chat.
 */
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DirectMessageApiController {

    private final DirectMessageService directMessageService;
    private final DirectMessageConversationJpaRepository conversationRepository;
    private final DirectMessageJpaRepository messageRepository;
    private final PersonJpaRepository personRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartConversationRequest {
        private List<Long> otherPersonIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        private String body;
    }

    @GetMapping("/conversations")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getConversations(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Map<Long, Long> unreadByConversation = directMessageService.getUnreadSummary(person).unreadByConversation();
        List<Map<String, Object>> conversations = conversationRepository.findByParticipantsContaining(person)
                .stream()
                .map(conversation -> conversationSummary(conversation, person, unreadByConversation))
                .toList();

        return new ResponseEntity<>(conversations, HttpStatus.OK);
    }

    /**
     * Drives the navbar notification bell: {@code count} is how many different people
     * have sent the caller messages they haven't read yet (people, not messages).
     */
    @GetMapping("/unread")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getUnread(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        DirectMessageService.UnreadSummary summary = directMessageService.getUnreadSummary(person);
        return new ResponseEntity<>(Map.of(
                "count", summary.people().size(),
                "people", summary.people(),
                "conversations", summary.unreadByConversation()), HttpStatus.OK);
    }

    @PostMapping("/conversations")
    @Transactional
    public ResponseEntity<Object> startConversation(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody StartConversationRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        if (request == null || request.getOtherPersonIds() == null || request.getOtherPersonIds().isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "otherPersonIds is required"), HttpStatus.BAD_REQUEST);
        }

        Set<Long> seenIds = new LinkedHashSet<>();
        List<Person> others = request.getOtherPersonIds().stream()
                .filter(id -> id != null && seenIds.add(id))
                .map(id -> personRepository.findById(id).orElse(null))
                .filter(other -> other != null && !other.getId().equals(person.getId()))
                .toList();

        if (others.isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "No valid recipients found"), HttpStatus.BAD_REQUEST);
        }

        // Must run before a new conversation is persisted: a query afterwards auto-flushes it, and the
        // commit-time flush then compares its participants by Person.hashCode(), which recurses forever
        // through Person <-> Bank (both Lombok @Data).
        Map<Long, Long> unreadByConversation = directMessageService.getUnreadSummary(person).unreadByConversation();
        DirectMessageConversation conversation = directMessageService.getOrCreateConversation(person, others);
        return new ResponseEntity<>(conversationSummary(conversation, person, unreadByConversation), HttpStatus.OK);
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getMessages(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        List<Map<String, Object>> messages = directMessageService.getMessages(conversation).stream()
                .map(this::messageSummary)
                .toList();

        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @PostMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<Object> postMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @RequestBody SendMessageRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }

        String body = request.getBody().trim();
        if (body.length() > DirectMessageService.MAX_BODY_LENGTH) {
            return new ResponseEntity<>(Map.of("error", "body is too long"), HttpStatus.BAD_REQUEST);
        }

        DirectMessageEvent event = directMessageService.postMessage(conversation, person, body);
        return new ResponseEntity<>(event, HttpStatus.OK);
    }

    @PatchMapping("/conversations/{id}/messages/{messageId}")
    @Transactional
    public ResponseEntity<Object> editMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @PathVariable("messageId") Long messageId,
            @RequestBody SendMessageRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        // The message must exist AND belong to this conversation (blocks editing via a mismatched URL).
        Optional<DirectMessage> messageOpt = messageRepository.findById(messageId)
                .filter(m -> m.getConversation().getId().equals(conversation.getId()));
        if (messageOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessage message = messageOpt.get();
        if (!directMessageService.isSender(message, person)) {
            return new ResponseEntity<>(Map.of("error", "You can only edit your own messages"), HttpStatus.FORBIDDEN);
        }

        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }
        String body = request.getBody().trim();
        if (body.length() > DirectMessageService.MAX_BODY_LENGTH) {
            return new ResponseEntity<>(Map.of("error", "body is too long"), HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(directMessageService.editMessage(message, body), HttpStatus.OK);
    }

    @PostMapping("/conversations/{id}/read")
    @Transactional
    public ResponseEntity<Object> markRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        directMessageService.markRead(conversation, person);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    private Person currentPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepository.findByUid(userDetails.getUsername());
    }

    private Map<String, Object> conversationSummary(
            DirectMessageConversation conversation, Person self, Map<Long, Long> unreadByConversation) {
        List<Map<String, Object>> otherParticipants = conversation.getParticipants().stream()
                .filter(participant -> !participant.getId().equals(self.getId()))
                .map(participant -> Map.<String, Object>of(
                        "id", participant.getId(),
                        "uid", participant.getUid(),
                        "name", participant.getName()))
                .toList();

        return Map.of(
                "id", conversation.getId(),
                "participants", otherParticipants,
                "unreadCount", unreadByConversation.getOrDefault(conversation.getId(), 0L));
    }

    private Map<String, Object> messageSummary(DirectMessage message) {
        Person sender = message.getSender();
        // Not Map.of: editedAt is null for unedited messages and Map.of rejects null values.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", message.getId());
        summary.put("senderUid", sender.getUid());
        summary.put("senderName", sender.getName());
        summary.put("body", message.getBody());
        summary.put("sentAt", message.getSentAt().toString());
        summary.put("editedAt", message.getEditedAt() == null ? null : message.getEditedAt().toString());
        return summary;
    }
}
