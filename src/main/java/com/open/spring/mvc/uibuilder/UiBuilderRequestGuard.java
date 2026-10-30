package com.open.spring.mvc.uibuilder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Lets only the OCS pages call the signed-in UI Builder routes. A request must say X-Origin: client,
 * come from one of the exact origins in ui-builder.allowed-origins (not the CORS patterns, which
 * accept every *.opencodingsociety.com student server that would also receive the login cookie),
 * and send its body as application/json when it writes. A cross-site form can do none of these.
 * Every route except the two public GETs calls check() first, reads included.
 */
@Component
public class UiBuilderRequestGuard {

    private final Set<String> allowedOrigins;

    public UiBuilderRequestGuard(@Value("${ui-builder.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream()
                .map(UiBuilderRequestGuard::normalize)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Throws 403 {"error":"origin_rejected"} unless the request came from an OCS page. */
    public void check(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        boolean fromClient = "client".equals(request.getHeader("X-Origin"));
        boolean knownOrigin = origin != null && allowedOrigins.contains(normalize(origin));
        boolean jsonIfWriting = !isWrite(request.getMethod()) || isJson(request.getContentType());
        if (!(fromClient && knownOrigin && jsonIfWriting)) {
            throw new UiBuilderException(HttpStatus.FORBIDDEN, "origin_rejected", Map.of());
        }
    }

    private static boolean isWrite(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    // Scheme and host are case-insensitive; browsers never send a trailing slash, but a configured value might.
    private static String normalize(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
