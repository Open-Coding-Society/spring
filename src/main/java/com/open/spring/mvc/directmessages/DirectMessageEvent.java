package com.open.spring.mvc.directmessages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The payload broadcast to /topic/dm/{conversationId} whenever a new message
 * is posted. Plain data holder, mirrors GroupChatEvent's role for group chat.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectMessageEvent {
    private Long id;
    private Long conversationId;
    private String senderUid;
    private String senderName;
    private String senderPfp;
    private String body;
    private String sentAt;
    /** "edited" or "deleted"; absent (null) for an ordinary new-message event. */
    private String type;
    private boolean deleted;
    private boolean edited;
}
