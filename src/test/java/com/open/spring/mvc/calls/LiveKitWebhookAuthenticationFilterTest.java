package com.open.spring.mvc.calls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;

/** Who LiveKitWebhookAuthenticationFilter signs in, and that the controller can still read the body. */
class LiveKitWebhookAuthenticationFilterTest {

    private static final byte[] BODY = "{\"event\":\"participant_left\"}".getBytes(StandardCharsets.UTF_8);

    private ServletRequest passedOn;
    private Authentication signedIn;
    private byte[] bodyDownstream;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSignedWebhookGetsTheWebhookRoleAndKeepsItsBody() throws Exception {
        run(webhook(BODY, signed(BODY)), configured());

        assertThat(signedIn).isNotNull();
        assertThat(signedIn.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(LiveKitWebhookAuthenticationFilter.ROLE);
        assertThat(bodyDownstream).isEqualTo(BODY);
    }

    @Test
    void anUnsignedWebhookStaysSignedOutAndKeepsItsBody() throws Exception {
        run(webhook(BODY, null), configured());

        assertThat(signedIn).isNull();
        assertThat(bodyDownstream).isEqualTo(BODY);
    }

    @Test
    void aSignatureForAnotherBodyStaysSignedOut() throws Exception {
        byte[] other = "{\"event\":\"room_finished\"}".getBytes(StandardCharsets.UTF_8);
        run(webhook(BODY, signed(other)), configured());

        assertThat(signedIn).isNull();
    }

    @Test
    void aBodyOverTheCapStaysSignedOutEvenWhenSigned() throws Exception {
        byte[] big = new byte[CallWebhookController.MAX_BODY_BYTES + 1];
        run(webhook(big, signed(big)), configured());

        assertThat(signedIn).isNull();
    }

    @Test
    void withoutTheCallsSettingsNobodyIsSignedIn() throws Exception {
        run(webhook(BODY, signed(BODY)), new StaticListableBeanFactory().getBeanProvider(CallsProperties.class));

        assertThat(signedIn).isNull();
    }

    @Test
    void otherRequestsPassThroughUntouched() throws Exception {
        for (MockHttpServletRequest other : new MockHttpServletRequest[] {
                request("POST", "/api/calls", BODY, signed(BODY)),
                request("GET", "/api/calls/webhook", BODY, signed(BODY))}) {
            run(other, configured());

            assertThat(passedOn).isSameAs(other);
            assertThat(signedIn).isNull();
        }
    }

    private void run(MockHttpServletRequest request, ObjectProvider<CallsProperties> settings) throws Exception {
        FilterChain rest = (req, res) -> {
            passedOn = req;
            signedIn = SecurityContextHolder.getContext().getAuthentication();
            bodyDownstream = req.getInputStream().readAllBytes();
        };
        new LiveKitWebhookAuthenticationFilter(settings).doFilter(request, new MockHttpServletResponse(), rest);
    }

    private static ObjectProvider<CallsProperties> configured() {
        CallsProperties properties = new CallsProperties();
        properties.getLivekit().setUrl("ws://localhost:7880");
        properties.getLivekit().setApiKey(LiveKitWebhookVerifierTest.KEY);
        properties.getLivekit().setApiSecret(LiveKitWebhookVerifierTest.SECRET);
        return new StaticListableBeanFactory(Map.of("callsProperties", properties)).getBeanProvider(CallsProperties.class);
    }

    private static String signed(byte[] body) throws Exception {
        return LiveKitWebhookVerifierTest.signature(body, LiveKitWebhookVerifierTest.KEY, LiveKitWebhookVerifierTest.SECRET);
    }

    private static MockHttpServletRequest webhook(byte[] body, String authorization) {
        return request("POST", "/api/calls/webhook", body, authorization);
    }

    private static MockHttpServletRequest request(String method, String path, byte[] body, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
