package com.open.spring.mvc.cspathway;

/**
 * A student flagged for help again before the cooldown ended.
 * Keeps one double-click or a stuck key from flooding the teacher's list.
 */
public class PathwayHelpExceptionTooSoon extends RuntimeException {
    private final long retryAfterSeconds;

    public PathwayHelpExceptionTooSoon(long retryAfterSeconds) {
        super("Your teacher already has your help request. You can send another in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
