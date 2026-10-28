package com.open.spring.mvc.toolchain;

/**
 * A student asked the chat helper again before the short cooldown ended.
 * Keeps a stuck Enter key or a script from spending the shared Gemini quota.
 */
public class ToolchainChatExceptionTooSoon extends RuntimeException {
    private final long retryAfterSeconds;

    public ToolchainChatExceptionTooSoon(long retryAfterSeconds) {
        super("Give the helper a moment. You can ask again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
