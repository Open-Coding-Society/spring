package com.open.spring.mvc.cspathway;

/**
 * Body of POST /api/cs-pathway/help-requests.
 * The student comes from the JWT cookie, never from this body.
 */
public record CsPathwayHelpRequestBody(String levelKey, String station, String message) {
}
