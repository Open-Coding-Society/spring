package com.open.spring.mvc.calls;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Signs in LiveKit's webhook. A POST /api/calls/webhook that carries LiveKit's signature (a token
 * made with the LiveKit API secret whose sha256 claim is the hash of the exact body, at most 64 KB)
 * gets ROLE_CALLS_WEBHOOK, the only authority SecurityConfig lets through on that route. No account
 * holds that role, so the chain refuses any other request there before the controller runs: 401, or
 * 403 for a signed-in account. CallWebhookController still checks the signature itself. Other
 * requests pass through untouched.
 */
public class LiveKitWebhookAuthenticationFilter extends OncePerRequestFilter {

    /** The authority SecurityConfig asks for on POST /api/calls/webhook. */
    public static final String ROLE = "ROLE_CALLS_WEBHOOK";

    private static final String PATH = "/api/calls/webhook";

    // Through a provider: SecurityConfig is also built in test slices and in the RBAC probe, which have
    // no calls beans. Without the settings nobody is signed in.
    private final ObjectProvider<CallsProperties> properties;

    public LiveKitWebhookAuthenticationFilter(ObjectProvider<CallsProperties> properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CallsProperties settings = properties.getIfAvailable();
        if (settings == null || !settings.getLivekit().isConfigured()
                || request.getContentLengthLong() > CallWebhookController.MAX_BODY_BYTES) {
            chain.doFilter(request, response);
            return;
        }
        // The hash covers the exact body, so it is read here; the controller gets a copy it can read again.
        byte[] body = request.getInputStream().readNBytes(CallWebhookController.MAX_BODY_BYTES + 1);
        CallsProperties.LiveKit liveKit = settings.getLivekit();
        if (body.length <= CallWebhookController.MAX_BODY_BYTES && LiveKitWebhookVerifier.verify(body,
                request.getHeader(HttpHeaders.AUTHORIZATION), liveKit.getApiKey(), liveKit.getApiSecret())) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "livekit", null, List.of(new SimpleGrantedAuthority(ROLE))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(new ReadBody(request, body), response);
    }

    /** The request with its body already read, handed on so the body can be read again. */
    private static final class ReadBody extends HttpServletRequestWrapper {

        private final byte[] body;

        ReadBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("the body is already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
