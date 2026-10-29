package com.open.spring.mvc.uibuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.open.spring.mvc.uibuilder.UiBuilderPageService.Outcome;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Result;

/**
 * A refused UI Builder request. UiBuilderExceptionHandler writes it as {"error": code, ...details},
 * where error is a short snake_case code the Pages store turns into its typed errors and text.
 * statusOf is the one place a service outcome becomes an HTTP status.
 */
public class UiBuilderException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final Map<String, Object> details;

    public UiBuilderException(HttpStatus status, String error, Map<String, Object> details) {
        super(error);
        this.status = status;
        this.error = error;
        // Kept in order and allowed to hold nulls (savedByName can be one), unlike Map.copyOf.
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    /** Extra top-level fields of the error body, such as currentVersion or details. */
    public Map<String, Object> getDetails() {
        return details;
    }

    static UiBuilderException signInRequired() {
        return new UiBuilderException(HttpStatus.UNAUTHORIZED, "unauthorized", Map.of());
    }

    /** The HTTP status of every service outcome, for both controllers. */
    public static HttpStatus statusOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> HttpStatus.OK;
            case CREATED -> HttpStatus.CREATED;
            case NO_CONTENT -> HttpStatus.NO_CONTENT;
            case INVALID_SPEC, INVALID_GROUP, INVALID_VISIBILITY, INVALID_UNTIL, INVALID_COURSE, INVALID_SCOPE,
                    SLUG_REQUIRED, INVALID_SLUG, SLUG_RESERVED, LOOPBACK_SOURCE, OWN_PAGE -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_CONFLICT, SLUG_TAKEN, SLUG_LOCKED, NOT_PUBLISHED, PUBLISHED_CHANGED, TOO_MANY_PAGES -> HttpStatus.CONFLICT;
        };
    }

    /** The error code a client sees for an outcome: its name in lower case, such as version_conflict. */
    public static String codeOf(Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    /**
     * A successful result as a response that browsers and proxies never cache (pages are private
     * until published), or the refusal thrown for UiBuilderExceptionHandler to write.
     */
    static <T> ResponseEntity<T> respond(Result<T> result) {
        if (!result.succeeded()) {
            throw new UiBuilderException(statusOf(result.outcome()), codeOf(result.outcome()), result.details());
        }
        return ResponseEntity.status(statusOf(result.outcome())).cacheControl(CacheControl.noStore()).body(result.body());
    }
}
