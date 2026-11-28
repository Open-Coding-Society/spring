package com.open.spring.mvc.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The text a phone shows for a direct message: the words a reader sees in the chat, without
 * the markers the page reads ([[reply:…]], [[files:…]], [[code:…]], [[pin:…]]) or the rich
 * composer's HTML.
 */
class PushNotificationSnippetTest {

    @Test
    void chatMarkersAreLeftOut() {
        assertEquals("thanks!", PushNotificationService.snippet("[[reply:9|Bob|hey%20there]] thanks!"));
        assertEquals("look", PushNotificationService.snippet("[[files:f~shots%2Fa.png~5]] look"));
        // Pages escapes brackets, "|", "%", "<", ">" and "&" in code, but keeps quotes, tabs and newlines.
        assertEquals("try this", PushNotificationService.snippet(
                "[[code:python|print(\"hi\")\nfor i in range(3):\n\tprint(i)]] try this"));
        assertEquals("see this", PushNotificationService.snippet(
                "[[reply:9|Bob|hey]] [[files:f~a.txt~1]] [[code:java|int x;]] see this"));
    }

    @Test
    void aMessageThatIsOnlyAMarkerSaysWhatItDid() {
        assertEquals("pinned a message", PushNotificationService.snippet("[[pin:401]]"));
        assertEquals("unpinned a message", PushNotificationService.snippet("[[unpin:401]]"));
        assertEquals("sent code", PushNotificationService.snippet("[[code:python|print(1)]]"));
        assertEquals("shared files", PushNotificationService.snippet("[[files:f~a.png~5]]"));
        // A reply that only attaches an image has nothing but its reply marker in the body.
        assertEquals("sent a message", PushNotificationService.snippet("[[reply:9|Bob|hey]]"));
    }

    @Test
    void theComposersHtmlBecomesPlainText() {
        assertEquals("check https://github.com/o/r/pull/3", PushNotificationService.snippet(
                "check <a href=\"https://github.com/o/r/pull/3\" target=\"_blank\" rel=\"noopener noreferrer\">"
                        + "https://github.com/o/r/pull/3</a>&nbsp;"));
        assertEquals("Fish & chips tonight", PushNotificationService.snippet("<b>Fish</b> &amp; chips<br>tonight"));
        assertEquals("a < b", PushNotificationService.snippet("a &lt; b"));
    }

    @Test
    void longTextIsCutAndBlankStaysBlank() {
        String cut = PushNotificationService.snippet("x".repeat(200));
        assertEquals(140, cut.length());
        assertEquals('…', cut.charAt(139));
        assertEquals("", PushNotificationService.snippet(null));
        assertEquals("", PushNotificationService.snippet("   "));
    }

    @Test
    void markerOpeningsThatNeverCloseStayFast() {
        // None of these markers closes, so a pattern that rescans the rest of the body at each
        // "[[" takes seconds here; matching has to stay linear.
        String body = "[[code:".repeat(20_000);
        String text = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> PushNotificationService.snippet(body));
        assertEquals(140, text.length());
    }
}
