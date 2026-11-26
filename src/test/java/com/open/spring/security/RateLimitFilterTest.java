package com.open.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.calls.LiveKitWebhookAuthenticationFilter;

/** Which bucket a request's rate limit comes out of. */
class RateLimitFilterTest {

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAccountNamedLivekitCantUseUpTheWebhooksLimit() throws Exception {
        RateLimitFilter limiter = new RateLimitFilter();
        ReflectionTestUtils.setField(limiter, "exemptPaths", "");

        // Anyone can sign up as "livekit", the name LiveKitWebhookAuthenticationFilter signs LiveKit in as.
        signIn("livekit", "ROLE_STUDENT");
        int sent = 0;
        while (send(limiter, "/api/people") != 429) {
            sent++;
            assertThat(sent).as("the account's bucket never ran out").isLessThan(10_000);
        }

        signIn("livekit", LiveKitWebhookAuthenticationFilter.ROLE);
        assertThat(send(limiter, "/api/calls/webhook")).isEqualTo(200);
    }

    private static void signIn(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private static int send(RateLimitFilter limiter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        limiter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
