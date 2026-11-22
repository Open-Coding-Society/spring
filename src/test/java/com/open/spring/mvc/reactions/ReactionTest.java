package com.open.spring.mvc.reactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The reaction keys are a contract with the Pages client: Messages' reactions.js lists the
 * same keys in the same order, and a route's {reaction} is read with fromKey.
 */
class ReactionTest {

    @Test
    void everyKeyRoundTrips() {
        for (Reaction reaction : Reaction.values()) {
            assertEquals(Optional.of(reaction), Reaction.fromKey(reaction.key()));
        }
        List<String> keys = Arrays.stream(Reaction.values()).map(Reaction::key).toList();
        assertEquals(List.of("like", "love", "haha", "wow", "sad", "celebrate"), keys);
    }

    @Test
    void unknownKeysAreRefused() {
        assertTrue(Reaction.fromKey(null).isEmpty());
        assertTrue(Reaction.fromKey("").isEmpty());
        assertTrue(Reaction.fromKey("LIKE").isEmpty());
        assertTrue(Reaction.fromKey("thumbsup").isEmpty());
    }
}
