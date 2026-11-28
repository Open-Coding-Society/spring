package com.open.spring.mvc.groups;

/**
 * S3 was unavailable for a moment while a file was being shared: it timed out, couldn't be
 * reached or answered with a 5xx (a throttle, an outage). Either it couldn't say whether the
 * path is taken, so the upload is refused rather than risk writing over a file, or the write
 * itself failed. The file isn't shared, and the API answers 503 with Retry-After, since S3 being
 * unavailable for a moment isn't a bug in this server.
 */
public class SharedFileStorageUnavailableException extends RuntimeException {

    private final String path;

    public SharedFileStorageUnavailableException(String path, Throwable cause) {
        super("Could not share " + path + " in this group. Try again in a moment.", cause);
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
