package com.open.spring.mvc.groups;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.groups.GroupChatIdentityService.AuthenticatedIdentity;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.reactions.ChatType;
import com.open.spring.mvc.reactions.MessageReactionService;
import com.open.spring.mvc.reactions.MessageReactionService.MessageReactions;
import com.open.spring.mvc.reactions.Reaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/groups/chat")
@CrossOrigin
public class GroupChatApiController {

    // How long to wait before trying again when S3 was unavailable for a moment, checking or
    // writing a shared file or reading or saving a group's messages: a throttle or a short
    // outage clears in seconds.
    private static final int RETRY_AFTER_SECONDS = 5;

    // A saved group message's id is a UUID (GroupChatMessage), written in lowercase. Reactions
    // don't read the group's S3 file to check that the message exists, so the id at least has to
    // look like one; an uppercase copy would match some lookups and not others on MySQL.
    static final Pattern MESSAGE_ID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final GroupChatService groupChatService;
    private final GroupChatRealtimeService realtimeService;
    private final GroupChatReadService readService;
    private final GroupsJpaRepository groupsRepository;
    private final PersonJpaRepository personRepository;
    private final MessageReactionService reactionService;
    private final MentorAccess mentorAccess;

    public GroupChatApiController(
            GroupChatService groupChatService,
            GroupChatRealtimeService realtimeService,
            GroupChatReadService readService,
            GroupsJpaRepository groupsRepository,
            PersonJpaRepository personRepository,
            MessageReactionService reactionService,
            MentorAccess mentorAccess) {
        this.groupChatService = groupChatService;
        this.realtimeService = realtimeService;
        this.readService = readService;
        this.groupsRepository = groupsRepository;
        this.personRepository = personRepository;
        this.reactionService = reactionService;
        this.mentorAccess = mentorAccess;
    }

    /**
     * A mentor rule's refusal for the signed-in account and this group, or null when it may go on. Only a mentor
     * can be refused here; everyone else's chat works as before (see MentorRules).
     */
    private ResponseEntity<Object> mentorRefusal(Groups group) {
        return mentorAccess.toUse(currentPerson(), group).map(MentorRules.Refusal::toResponse).orElse(null);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileUploadRequest {
        private String filename;
        private String base64Data;
    }

    // --- Auth helpers ---
    // Only wired into editMessage/deleteMessage below (see their ownership checks) -- every
    // other endpoint here still has no auth enforcement, matching this controller's existing
    // (pre-existing, not changed here) permissive posture. isMember() stays commented out:
    // unused once edit/delete moved to a sender-identity check instead of membership.
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

    /** The signed-in Person, or null when nobody is signed in. */
    private Person currentPerson() {
        String uid = getCurrentUsername();
        return uid == null ? null : personRepository.findByUid(uid);
    }

    private AuthenticatedIdentity getCurrentIdentity() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getName())) {
            return null;
        }

        String uid = auth.getName();
        Person person = personRepository.findByUid(uid);

        if (person == null) {
            return null;
        }

        return new AuthenticatedIdentity(uid, person.getName());
    }

    private String resolveCurrentDisplayName() {
        String uid = getCurrentUsername();
        if (uid == null) {
            return null;
        }
        Person person = personRepository.findByUid(uid);
        return person == null ? null : person.getName();
    }

    // private boolean isMember(Groups group, String uid) {
    //     return group.getGroupMembers().stream()
    //             .anyMatch(member -> uid.equals(member.getUid()));
    // }

    @GetMapping("/analytics/{personId}")
    public ResponseEntity<?> getUserAnalytics(@PathVariable Long personId) {
        Person viewer = currentPerson();
        if (viewer != null && !personId.equals(viewer.getId())) {
            Optional<MentorRules.Refusal> refusal = MentorRules.manageGroups(viewer);
            if (refusal.isPresent()) {
                return refusal.get().toResponse();
            }
        }

        Optional<Person> personOpt = personRepository.findById(personId);
        if (personOpt.isEmpty()) {
            return new ResponseEntity<>("Person not found", HttpStatus.NOT_FOUND);
        }

        Person person = personOpt.get();
        List<Groups> groups = groupsRepository.findGroupsByPersonId(personId);

        Map<String, Object> analytics = groupChatService.getUserAnalytics(person.getName(), groups);
        analytics.put("personId", personId);
        analytics.put("personName", person.getName());

        return new ResponseEntity<>(analytics, HttpStatus.OK);
    }

    @GetMapping("/{groupId}/messages")
    public ResponseEntity<?> getMessages(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        String groupName = groupOpt.get().getName();
        List<GroupChatMessage> messages = groupChatService.getMessages(groupName);
        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @PostMapping("/{groupId}/messages")
    public ResponseEntity<?> postMessage(
            @PathVariable Long groupId,
            @RequestBody GroupChatMessage message) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        AuthenticatedIdentity identity = getCurrentIdentity();

        if (identity == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        if (message == null || message.getMessage() == null) {
            return new ResponseEntity<>("name and message are required", HttpStatus.BAD_REQUEST);
        }

        // Answered from what was saved: a second read could fail and answer an empty chat.
        List<GroupChatMessage> saved;
        try {
            saved = realtimeService.publishMessage(groupId, message.getMessage(), message.getImage(), identity);
        } catch (MessagesStorageUnavailableException ex) {
            return tryAgainLater(ex);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(saved, HttpStatus.OK);
    }

    @PatchMapping("/{groupId}/messages/{messageId}")
    public ResponseEntity<?> editMessage(
            @PathVariable Long groupId,
            @PathVariable String messageId,
            @RequestBody GroupChatMessage request) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        AuthenticatedIdentity identity = getCurrentIdentity();
        if (identity == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            return new ResponseEntity<>("message is required", HttpStatus.BAD_REQUEST);
        }

        Optional<List<GroupChatMessage>> saved;
        try {
            saved = realtimeService.editMessage(groupId, messageId, request.getMessage(), identity.getUid());
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        } catch (MessagesStorageUnavailableException ex) {
            return tryAgainLater(ex);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (saved.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(saved.get(), HttpStatus.OK);
    }

    @DeleteMapping("/{groupId}/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(
            @PathVariable Long groupId,
            @PathVariable String messageId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        AuthenticatedIdentity identity = getCurrentIdentity();
        if (identity == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        boolean deleted;
        try {
            deleted = realtimeService.deleteMessage(groupId, messageId, identity.getUid());
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        } catch (MessagesStorageUnavailableException ex) {
            return tryAgainLater(ex);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (!deleted) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * S3 couldn't read or save the group's messages, so the change isn't broadcast (a save that
     * timed out may still be stored). That's S3 being unavailable for a moment, not a bug here:
     * a 503, and when to try again.
     */
    private static ResponseEntity<String> tryAgainLater(MessagesStorageUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                .body(ex.getMessage());
    }

    /**
     * Every reaction in the group's chat, by message id: what the page draws when it opens the
     * chat. Like posting, it needs a signed-in person but not membership.
     */
    @GetMapping("/{groupId}/reactions")
    public ResponseEntity<?> getReactions(@PathVariable Long groupId) {
        if (currentPerson() == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }
        return new ResponseEntity<>(reactionService.forChat(ChatType.GROUP, groupId), HttpStatus.OK);
    }

    /** Adds the caller's reaction, by its lowercase key such as "like", and answers with the message's reactions. */
    @PutMapping("/{groupId}/messages/{messageId}/reactions/{reaction}")
    public ResponseEntity<?> addReaction(
            @PathVariable Long groupId,
            @PathVariable String messageId,
            @PathVariable String reaction) {
        return setReaction(groupId, messageId, reaction, true);
    }

    /** Removes the caller's reaction; removing one that isn't there still answers 200. */
    @DeleteMapping("/{groupId}/messages/{messageId}/reactions/{reaction}")
    public ResponseEntity<?> removeReaction(
            @PathVariable Long groupId,
            @PathVariable String messageId,
            @PathVariable String reaction) {
        return setReaction(groupId, messageId, reaction, false);
    }

    private ResponseEntity<?> setReaction(Long groupId, String messageId, String key, boolean on) {
        Person person = currentPerson();
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }
        Optional<Reaction> reaction = Reaction.fromKey(key);
        if (reaction.isEmpty()) {
            return new ResponseEntity<>("unknown reaction", HttpStatus.BAD_REQUEST);
        }
        if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            return new ResponseEntity<>("messageId is not a message id", HttpStatus.BAD_REQUEST);
        }

        List<MessageReactionService.ReactionSummary> reactions =
                realtimeService.setReaction(groupId, person, messageId, reaction.get(), on);
        return new ResponseEntity<>(new MessageReactions(messageId, reactions), HttpStatus.OK);
    }

    /**
     * Unread counts for the signed-in member's own groups, keyed by group id. Groups
     * with nothing unread are left out, so an empty map means nothing is waiting.
     */
    @GetMapping("/unread")
    public ResponseEntity<?> getUnread() {
        Person person = currentPerson();
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        Map<Long, Long> unreadByGroup = new HashMap<>(readService.unreadByGroup(person));
        // A mentor's count leaves out the groups their own list leaves out (MentorRules), so the bell never points
        // at a group they can't open or mark read.
        unreadByGroup.keySet().removeIf(groupId -> mentorAccess.refusesGroup(person, groupId));
        long total = unreadByGroup.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Object> body = new HashMap<>();
        body.put("unreadByGroup", unreadByGroup);
        body.put("total", total);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    /** Marks a group read, as of now, for the signed-in member. */
    @PostMapping("/{groupId}/read")
    public ResponseEntity<?> markRead(@PathVariable Long groupId) {
        Person person = currentPerson();
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        readService.markRead(groupOpt.get(), person);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping("/{groupId}/files")
    public ResponseEntity<?> getSharedFiles(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        String groupName = groupOpt.get().getName();
        List<Map<String, String>> files = groupChatService.listSharedFiles(groupName);
        return new ResponseEntity<>(files, HttpStatus.OK);
    }

    /**
     * The group's shared files by path, with each one's size and who shared it, but none of
     * their bytes: what a Files list needs. GET /{groupId}/files above still answers with
     * every file's bytes, for pages that read it that way.
     */
    @GetMapping("/{groupId}/files/index")
    public ResponseEntity<?> getSharedFileIndex(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }
        return new ResponseEntity<>(groupChatService.sharedFileIndex(groupOpt.get().getName()), HttpStatus.OK);
    }

    /** One shared file's bytes, named for download by the last part of its path. */
    @GetMapping("/{groupId}/files/content")
    public ResponseEntity<?> getSharedFileContent(@PathVariable Long groupId, @RequestParam String path) {
        String cleanPath = SharedFilePaths.clean(path);
        if (cleanPath == null) {
            return new ResponseEntity<>("path is not a valid shared file path", HttpStatus.BAD_REQUEST);
        }
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }
        byte[] bytes = groupChatService.readSharedFile(groupOpt.get().getName(), cleanPath);
        if (bytes == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(SharedFilePaths.lastSegment(cleanPath), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }

    @PostMapping("/{groupId}/files")
    public ResponseEntity<?> uploadSharedFile(
            @PathVariable Long groupId,
            @RequestBody FileUploadRequest request) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Object> refusal = mentorRefusal(groupOpt.get());
        if (refusal != null) {
            return refusal;
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        if (request == null || request.getFilename() == null || request.getBase64Data() == null) {
            return new ResponseEntity<>("filename and base64Data are required", HttpStatus.BAD_REQUEST);
        }
        // A file shared from a folder keeps its folders in its name, so the name is a path:
        // it has to stay inside the group's shared files.
        if (SharedFilePaths.clean(request.getFilename()) == null) {
            return new ResponseEntity<>("filename is not a valid path", HttpStatus.BAD_REQUEST);
        }

        try {
            // Resolved from the session, never taken from the request body: who shared a
            // file is attribution, and a client should not be able to claim someone else
            // did it. Null for an unauthenticated caller, which reads as "unknown".
            realtimeService.publishFile(groupId, resolveCurrentDisplayName(),
                    request.getFilename(), request.getBase64Data());
            Map<String, String> response = new HashMap<>();
            response.put("message", "File uploaded successfully");
            response.put("filename", request.getFilename());
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (SharedFileExistsException ex) {
            // Someone shared a file under this name first, and it is never written over.
            Map<String, String> response = new HashMap<>();
            response.put("message", ex.getMessage());
            response.put("filename", ex.getPath());
            return new ResponseEntity<>(response, HttpStatus.CONFLICT);
        } catch (SharedFileStorageUnavailableException ex) {
            // S3 was unavailable for a moment, checking the path or writing the file, so the file
            // isn't shared. That's not a bug here: a 503, and when to try again.
            Map<String, String> response = new HashMap<>();
            response.put("message", ex.getMessage());
            response.put("filename", ex.getPath());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                    .body(response);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>("Upload failed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
