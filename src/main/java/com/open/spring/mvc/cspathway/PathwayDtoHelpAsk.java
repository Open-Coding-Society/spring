package com.open.spring.mvc.cspathway;

import java.util.Date;

/**
 * Data Transfer Object (DTO) representing a single help request as displayed on the teacher's dashboard.
 * This record combines information from the help request entity with the student's display name.
 *
 * @param name       The display name of the student who made the request.
 * @param uid        The unique identifier of the student.
 * @param levelName  The human-readable name of the CS Pathway level.
 * @param station    The specific station or area where the student was located.
 * @param message    The content of the student's help message.
 * @param createdAt  The timestamp when the request was created.
 */
public record PathwayDtoHelpAsk(
        String name,
        String uid,
        String levelName,
        String station,
        String message,
        Date createdAt) {
}
