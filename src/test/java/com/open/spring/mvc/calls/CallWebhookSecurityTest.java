package com.open.spring.mvc.calls;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.security.CookieFactory;
import com.open.spring.security.JwtAuthenticationEntryPoint;
import com.open.spring.security.JwtRequestFilter;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.MvcSecurityConfig;
import com.open.spring.security.RateLimitFilter;
import com.open.spring.security.SecurityConfig;

/**
 * The webhook behind the real API filter chain. The chain lets POST /api/calls/webhook through only
 * with ROLE_CALLS_WEBHOOK, which only LiveKit's signature over the body gives, so anything else gets
 * the chain's own 401 ({"error":"Unauthorized"}) and never reaches the controller.
 */
@WebMvcTest(controllers = CallWebhookController.class)
@Import({SecurityConfig.class, MvcSecurityConfig.class, CookieFactory.class, JwtAuthenticationEntryPoint.class,
        JwtRequestFilter.class, RateLimitFilter.class})
@TestPropertySource(properties = {
        "person.default-password=not-used",
        "calls.enabled=true",
        "calls.livekit.url=ws://localhost:7880",
        "calls.livekit.api-key=" + LiveKitWebhookVerifierTest.KEY,
        "calls.livekit.api-secret=" + LiveKitWebhookVerifierTest.SECRET})
class CallWebhookSecurityTest {

    private static final byte[] BODY = "{\"event\":\"participant_joined\"}".getBytes(StandardCharsets.UTF_8);

    /** The calls settings, which a test slice doesn't build on its own. */
    @TestConfiguration
    @EnableConfigurationProperties(CallsProperties.class)
    static class Settings {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CallsProperties properties;

    @MockitoBean
    private CallWebhookService webhooks;

    @MockitoBean
    private PersonDetailsService personDetailsService;

    @MockitoBean
    private JwtTokenUtil jwtTokenUtil;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Test
    void anUnsignedRequestIsRefusedByTheChain() throws Exception {
        mvc.perform(webhook(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        verifyNoInteractions(webhooks);
    }

    @Test
    void aRequestSignedWithAnotherSecretIsRefusedByTheChain() throws Exception {
        String wrong = LiveKitWebhookVerifierTest.signature(BODY, LiveKitWebhookVerifierTest.KEY,
                "not-the-livekit-secret-0123456789abcdef");
        mvc.perform(webhook(BODY).header("Authorization", wrong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        verifyNoInteractions(webhooks);
    }

    @Test
    void aSignatureForAnotherBodyIsRefusedByTheChain() throws Exception {
        byte[] other = "{\"event\":\"room_finished\"}".getBytes(StandardCharsets.UTF_8);
        String forOther = LiveKitWebhookVerifierTest.signature(other, LiveKitWebhookVerifierTest.KEY,
                LiveKitWebhookVerifierTest.SECRET);
        mvc.perform(webhook(BODY).header("Authorization", forOther))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        verifyNoInteractions(webhooks);
    }

    @Test
    void whileCallsAreOffASignedRequestStillGetsTheControllers404() throws Exception {
        properties.setEnabled(false);
        try {
            String signed = LiveKitWebhookVerifierTest.signature(BODY, LiveKitWebhookVerifierTest.KEY,
                    LiveKitWebhookVerifierTest.SECRET);
            mvc.perform(webhook(BODY).header("Authorization", signed))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.reason").value("calls_disabled"));
            verifyNoInteractions(webhooks);
        } finally {
            properties.setEnabled(true);
        }
    }

    @Test
    void aSignedRequestReachesTheControllerWithItsWholeBody() throws Exception {
        String signed = LiveKitWebhookVerifierTest.signature(BODY, LiveKitWebhookVerifierTest.KEY,
                LiveKitWebhookVerifierTest.SECRET);
        mvc.perform(webhook(BODY).header("Authorization", signed))
                .andExpect(status().isOk());
        verify(webhooks).handle(aryEq(BODY));
    }

    private static MockHttpServletRequestBuilder webhook(byte[] body) {
        return post("/api/calls/webhook").contentType("application/webhook+json").content(body);
    }
}
