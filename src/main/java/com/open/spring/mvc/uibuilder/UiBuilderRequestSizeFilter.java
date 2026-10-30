package com.open.spring.mvc.uibuilder;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses UI Builder request bodies over 1 000 000 bytes with 413 {"error":"too_large"}, before
 * Jackson reads them. The application allows 50 MB uploads elsewhere, and a page spec is at most
 * 200 000 characters. A body sent without Content-Length (chunked) is read up to the limit here
 * and passed on from memory, so it can't grow past the limit either. Spring Boot registers this
 * filter after Spring Security, so a request without a login is refused before its body is read.
 */
@Component
public class UiBuilderRequestSizeFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 1_000_000;
    private static final String PREFIX = "/api/ui-builder";

    /**
     * Compares the decoded path, as Spring MVC matches it: "/api/ui%2Dbuilder/pages" reaches the same
     * controller as "/api/ui-builder/pages", so it gets the same limit.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path;
        try {
            path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException undecodable) {
            return false; // a path that can't be decoded gets the limit too
        }
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            tooLarge(response);
            return;
        }
        boolean hasBody = declared > 0 || request.getHeader("Transfer-Encoding") != null;
        if (!hasBody) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"too_large\"}");
        response.getWriter().flush();
    }

    /** The request with its body already read into memory. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body was already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
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
