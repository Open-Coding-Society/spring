package com.open.spring.mvc.groups;

import java.security.Principal;
import java.util.List;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;

import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.RequiredArgsConstructor;

/**
 * Controller class that handles incoming WebSocket messages for the group chat feature.
 * <p>
 * Methods annotated with {@link MessageMapping} will consume messages routed
 * with the defined destination. Interacts with {@link GroupChatRealtimeService}
 * and {@link GroupChatPresenceService} to handle joining/leaving groups and message delivery.
 */
@Controller
@RequiredArgsConstructor
public class GroupChatWebSocketController {

    private final GroupChatIdentityService identityService;
    private final GroupChatRealtimeService realtimeService;
    private final GroupChatPresenceService presenceService;
    private final MentorAccess mentorAccess;
    private final PersonJpaRepository personRepository;

    @MessageMapping("/groups.chat")
    public void handleGroupEvent(@Payload GroupChatEvent event,
                                 Principal principal,
                                 @Header("simpSessionId") String sessionId) {
        if (event == null || event.getContext() == null) {
            return;
        }

        // authenticated identity required for every event
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return;
        }

        Long groupId = event.getGroupId();
        String sender = resolveSender(event, principal); // deprecated, remove later
        String uid = principal.getName();

        // The mentor rules hold on the socket as over REST: a mentor sends, joins and types only in a group they may
        // use. Leaving always works.
        if (groupId != null && !"leaveGroup".equals(event.getContext()) && mentorRefuses(principal, groupId)) {
            return;
        }

        switch (event.getContext()) {
            case "joinGroup" -> {
                if (groupId == null) {
                    return;
                }
                presenceService.joinGroup(sessionId, groupId, sender);
                realtimeService.publishPresence(groupId, "joinGroupServer", sender, presenceService.getParticipants(groupId));
            }
            case "leaveGroup" -> {
                if (groupId == null) {
                    return;
                }
                presenceService.leaveGroup(sessionId, groupId);
                realtimeService.publishPresence(groupId, "leaveGroupServer", sender, presenceService.getParticipants(groupId));
            }
            case "sendMessage" -> {
                if (groupId == null || event.getMessage() == null || event.getMessage().isBlank()) {
                    return;
                }
                ensureJoined(sessionId, groupId, sender);
                try {
                    var identity = identityService.resolve(uid);
                    realtimeService.publishMessage(groupId, event.getMessage(), event.getImage(), identity);
                } catch (RuntimeException ex) {
                    realtimeService.publishError(groupId, sender, ex.getMessage());
                }
            }
            case "sendFile" -> {
                if (groupId == null || event.getFilename() == null || event.getFilename().isBlank()
                        || event.getBase64Data() == null || event.getBase64Data().isBlank()) {
                    return;
                }
                ensureJoined(sessionId, groupId, sender);
                try {
                    realtimeService.publishFile(groupId, sender, event.getFilename(), event.getBase64Data());
                } catch (RuntimeException ex) {
                    realtimeService.publishError(groupId, sender, ex.getMessage());
                }
            }
            case "typingStart" -> {
                if (groupId == null) {
                    return;
                }
                ensureJoined(sessionId, groupId, sender);
                realtimeService.publishTyping(groupId, sender, true);
            }
            case "typingStop" -> {
                if (groupId == null) {
                    return;
                }
                realtimeService.publishTyping(groupId, sender, false);
            }
            case "heartbeat" -> {
                if (groupId == null) {
                    return;
                }
                realtimeService.publishPresence(groupId, "heartbeatServer", sender, presenceService.getParticipants(groupId));
            }
            default -> {
                if (groupId != null) {
                    realtimeService.publishError(groupId, sender, "Unsupported context: " + event.getContext());
                }
            }
        }
    }

    /**
     * True when the mentor rules refuse this account the group. Only a mentor can be refused (see MentorRules). A
     * socket from Spring's own pages carries the roles of its sign-in, so an event from anyone that sign-in doesn't
     * call a mentor is answered without loading them; a role given since counts from the next sign-in.
     */
    private boolean mentorRefuses(Principal principal, Long groupId) {
        if (principal instanceof Authentication signIn && !MentorRules.isMentorRoles(roleNames(signIn))) {
            return false;
        }
        return mentorAccess.refusesGroup(personRepository.findByUid(principal.getName()), groupId);
    }

    private static List<String> roleNames(Authentication signIn) {
        return signIn.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private void ensureJoined(String sessionId, Long groupId, String sender) {
        if (!presenceService.isSessionInGroup(sessionId, groupId)) {
            presenceService.joinGroup(sessionId, groupId, sender);
            realtimeService.publishPresence(groupId, "joinGroupServer", sender, presenceService.getParticipants(groupId));
        }
    }

    private String resolveSender(GroupChatEvent event, Principal principal) {
        if (event != null && event.getSender() != null && !event.getSender().isBlank()) {
            return event.getSender().trim();
        }

        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }

        return "anonymous";
    }
}
