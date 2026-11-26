package com.open.spring.mvc.groups;

/**
 * S3 couldn't read a group's messages for now: it timed out, couldn't be reached or answered
 * with a 5xx (a throttle, an outage). A send writes the whole messages file back, so it's refused
 * rather than save the new message over the history, and the API answers 503 with Retry-After,
 * since S3 being unavailable for a moment isn't a bug in this server.
 */
public class MessagesReadFailedException extends RuntimeException {

    public MessagesReadFailedException(Throwable cause) {
        super("Could not read this group's messages, so the message was not sent. Try again in a moment.", cause);
    }
}
