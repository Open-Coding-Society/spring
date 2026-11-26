package com.open.spring.mvc.groups;

/**
 * S3 couldn't read or save a group's messages for now: it timed out, couldn't be reached or
 * answered with a 5xx (a throttle, an outage). Sending, editing and deleting a message each read
 * the whole messages file and write it back, so the change is refused rather than half done, and
 * the API answers 503 with Retry-After, since S3 being unavailable for a moment isn't a bug in
 * this server.
 */
public class MessagesStorageUnavailableException extends RuntimeException {

    public MessagesStorageUnavailableException(Throwable cause) {
        super("Could not save this change to the group's chat right now. Try again in a moment.", cause);
    }
}
