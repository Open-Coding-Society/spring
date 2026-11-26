package com.open.spring.mvc.groups;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.groups.GroupChatIdentityService.AuthenticatedIdentity;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.reactions.ChatType;
import com.open.spring.mvc.reactions.MessageReactionService;
import com.open.spring.mvc.reactions.Reaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service to handle the real-time publishing of group chat messages and presence events.
 * It uses {@link SimpMessagingTemplate} to broadcast messages to corresponding specific group topics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatRealtimeService {

    public static final String GROUP_TOPIC_PREFIX = "/topic/group/";

    private final GroupChatService groupChatService;
    private final GroupsJpaRepository groupsRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageReactionService reactionService;

    /** Saves the message, tells the group, and returns the group's messages as saved. */
    public List<GroupChatMessage> publishMessage(Long groupId, String message, String image, AuthenticatedIdentity identity) {
        Groups group = getGroupOrThrow(groupId);

        if ((message == null || message.isBlank()) && (image == null || image.isBlank())) {
            throw new IllegalArgumentException("Message or image must be provided");
        }

        String date = Instant.now().toString();

        GroupChatMessage persisted = new GroupChatMessage(identity.getDisplayName(), identity.getUid(), message, date, image);
        List<GroupChatMessage> saved = groupChatService.addMessage(group.getName(), persisted);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendMessageServer")
                .groupId(groupId)
                .messageId(persisted.getId())
                .sender(persisted.getAuthor())
                .message(persisted.getMessage())
                .image(persisted.getImage())
                .date(persisted.getDate())
                .build();

        broadcastToGroup(groupId, event);
        return saved;
    }

    /**
     * Edits the message, tells the group, and returns the group's messages as saved, or nothing
     * when the message isn't in this group. Throws {@link SecurityException} if the
     * authenticated requester's uid is not the author uid.
     */
    public Optional<List<GroupChatMessage>> editMessage(Long groupId, String messageId, String newMessage, String requesterUid) {
        Groups group = getGroupOrThrow(groupId);
        Optional<List<GroupChatMessage>> saved = groupChatService.editMessage(group.getName(), messageId, newMessage, requesterUid);
        if (saved.isEmpty()) {
            return saved;
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("editMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .message(newMessage)
                .edited(true)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return saved;
    }

    /**
     * Deletes the message and its reactions and tells the group, or returns false and tells no
     * one when the message isn't in this group. Throws {@link SecurityException} if the
     * authenticated requester didn't send it.
     */
    public boolean deleteMessage(Long groupId, String messageId, String requesterUid) {
        Groups group = getGroupOrThrow(groupId);
        if (!groupChatService.deleteMessage(group.getName(), messageId, requesterUid)) {
            // Telling the group about a message that isn't saved would take it off everyone's screen.
            return false;
        }
        try {
            reactionService.removeAllFor(ChatType.GROUP, groupId, messageId);
        } catch (DataAccessException e) {
            // The message is already gone from S3, so the group still has to hear about it, and a
            // retry would find nothing to delete. Its reactions stay in the table, where no message
            // shows them.
            log.warn("Could not remove the reactions of deleted message {} in group {}", messageId, groupId, e);
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("deleteMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return true;
    }

    /**
     * Adds ({@code on}) or removes {@code person}'s reaction to a message, then tells the
     * group's topic with a reactionsServer frame. Reactions are kept in the database, never in
     * the group's S3 message file, so a reaction can't change or drop a message.
     */
    public List<MessageReactionService.ReactionSummary> setReaction(
            Long groupId, Person person, String messageId, Reaction reaction, boolean on) {
        getGroupOrThrow(groupId);
        if (on) {
            reactionService.add(ChatType.GROUP, groupId, messageId, person, reaction);
        } else {
            reactionService.remove(ChatType.GROUP, groupId, messageId, person, reaction);
        }
        List<MessageReactionService.ReactionSummary> reactions =
                reactionService.forMessage(ChatType.GROUP, groupId, messageId);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("reactionsServer")
                .groupId(groupId)
                .messageId(messageId)
                .reactions(reactions)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return reactions;
    }

    /**
     * Stores a shared file and tells the group's topic about it. The frame names the file and
     * its size but carries no bytes: a big file sent to every member's socket could overflow
     * its send buffer and close it, and a member downloads a file by path when they open it.
     */
    public GroupChatEvent publishFile(Long groupId, String sender, String filename, String base64Data) {
        Groups group = getGroupOrThrow(groupId);
        String path = SharedFilePaths.clean(filename);
        if (path == null) {
            throw new IllegalArgumentException("Invalid file path");
        }

        groupChatService.uploadSharedFile(group.getName(), path, base64Data, sender);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendFileServer")
                .groupId(groupId)
                .sender(sender)
                .filename(path)
                .size(decodedLength(base64Data))
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return event;
    }

    public void publishPresence(Long groupId, String context, String sender, java.util.List<String> participants) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(context)
                .groupId(groupId)
                .sender(sender)
                .participants(participants)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishTyping(Long groupId, String sender, boolean typing) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(typing ? "typingStartServer" : "typingStopServer")
                .groupId(groupId)
                .sender(sender)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishError(Long groupId, String sender, String errorMessage) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context("errorServer")
                .groupId(groupId)
                .sender(sender)
                .error(errorMessage)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    /** How many bytes a base64 string holds, without decoding a second copy of a big file. */
    static long decodedLength(String base64Data) {
        int length = base64Data.length();
        int padding = 0;
        if (length > 0 && base64Data.charAt(length - 1) == '=') padding++;
        if (length > 1 && base64Data.charAt(length - 2) == '=') padding++;
        return Math.max(0, (long) length / 4 * 3 - padding);
    }

    private void broadcastToGroup(Long groupId, GroupChatEvent event) {
        messagingTemplate.convertAndSend(GROUP_TOPIC_PREFIX + groupId, event);
    }

    private Groups getGroupOrThrow(Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            throw new IllegalArgumentException("Group not found");
        }
        return groupOpt.get();
    }
}
