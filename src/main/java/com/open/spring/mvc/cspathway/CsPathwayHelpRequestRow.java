package com.open.spring.mvc.cspathway;

import java.util.Date;

/**
 * One help request as the teacher page shows it.
 */
public record CsPathwayHelpRequestRow(
        String name,
        String uid,
        String levelName,
        String station,
        String message,
        Date createdAt) {
}
