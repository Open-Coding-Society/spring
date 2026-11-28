package com.open.spring.mvc.reactions;

import java.util.Arrays;
import java.util.Optional;

/**
 * The emoji a message can get. A row stores the enum name; routes and JSON use the lowercase
 * key. Pages' Messages reactions.js lists the same keys in the same order, so change both
 * together.
 */
public enum Reaction {
    LIKE("like"),
    LOVE("love"),
    HAHA("haha"),
    WOW("wow"),
    SAD("sad"),
    CELEBRATE("celebrate");

    private final String key;

    Reaction(String key) {
        this.key = key;
    }

    /** The lowercase name a route and the page use, such as "like". */
    public String key() {
        return key;
    }

    /** The reaction with exactly this key, or empty for anything else, "LIKE" included. */
    public static Optional<Reaction> fromKey(String key) {
        return Arrays.stream(values()).filter(reaction -> reaction.key.equals(key)).findFirst();
    }
}
