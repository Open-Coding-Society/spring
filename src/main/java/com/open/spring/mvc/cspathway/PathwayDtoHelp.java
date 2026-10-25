package com.open.spring.mvc.cspathway;

/**
 * Data Transfer Object (DTO) representing the body of a POST request to /api/cs-pathway/help-requests.
 * This record is used to capture the context of a student's request for assistance.
 *
 * @param levelKey The identifier for the specific CS Pathway level where the request originated.
 * @param station  The specific station or area within the level where the student is located (may be null).
 * @param message  The text content of the student's help request.
 */
public record PathwayDtoHelp(
    String levelKey, 
    String station, 
    String message) {
}
