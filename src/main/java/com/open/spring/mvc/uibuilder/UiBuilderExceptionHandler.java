package com.open.spring.mvc.uibuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Writes every UI Builder error as JSON {"error": code, ...}. Answering here keeps errors out of
 * the /error dispatch, which this app's security chain turns into a redirect to the login page.
 * It runs before the app-wide advice and only for the two UI Builder controllers.
 */
@RestControllerAdvice(assignableTypes = {UiBuilderPageApiRestController.class, UiBuilderPublishedApiRestController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UiBuilderExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(UiBuilderExceptionHandler.class);
    // The slug column's unique key: "slug" (MySQL names it after the column), table-qualified on MySQL 8.
    private static final Pattern SLUG_CONSTRAINT = Pattern.compile("(?i)(?:ui_builder_page\\.)?slug");
    private static final Pattern SLUG_MESSAGE = Pattern.compile(
            "(?i)for key '(?:ui_builder_page\\.)?slug'|ui_builder_page\\.slug\\b|constraint \\[(?:ui_builder_page\\.)?slug\\]");

    @ExceptionHandler(UiBuilderException.class)
    public ResponseEntity<Map<String, Object>> refused(UiBuilderException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.getError());
        body.putAll(exception.getDetails());
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * 409 slug_taken when two pages published one address at once (on MySQL both can pass the check
     * inside their own row locks, and the UNIQUE slug column stops the second). Any other integrity
     * error (a value too long for its column, say) is a page Spring couldn't store: 400 invalid_spec.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrityViolation(DataIntegrityViolationException exception) {
        String cause = exception.getMostSpecificCause().getMessage();
        if (isSlugConflict(exception)) {
            logger.warn("UI Builder publish hit the unique slug: {}", cause);
            return error(HttpStatus.CONFLICT, "slug_taken");
        }
        logger.warn("UI Builder write broke a database constraint: {}", cause);
        return error(HttpStatus.BAD_REQUEST, "invalid_spec");
    }

    /**
     * Whether the violated constraint is ui_builder_page's UNIQUE slug: by the constraint name
     * Hibernate read from the error, or by the database's own message (MySQL "for key
     * 'ui_builder_page.slug'", SQLite "UNIQUE constraint failed: ui_builder_page.slug").
     */
    static boolean isSlugConflict(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && SLUG_CONSTRAINT.matcher(violation.getConstraintName()).matches()) {
                return true;
            }
            if (cause.getMessage() != null && SLUG_MESSAGE.matcher(cause.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, JsonProcessingException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_spec");
    }

    /** An id too big for a long can't name a page, so it is answered like any page that doesn't exist. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found");
    }

    /**
     * A body that isn't JSON is refused before the controller (and its guard) runs; answer it as
     * the guard would, since page requests always send application/json.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> notJson(HttpMediaTypeNotSupportedException exception) {
        return error(HttpStatus.FORBIDDEN, "origin_rejected");
    }

    /** The row lock wasn't granted in time (SQLite busy timeout, a MySQL lock wait or deadlock); the client retries. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> busy(PessimisticLockingFailureException exception) {
        logger.warn("UI Builder write could not take its lock: {}", exception.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "busy");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", code));
    }
}
