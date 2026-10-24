package com.open.spring.mvc.cspathway;

import java.util.Date;

/**
 * One help request as the teacher page shows it.
 */
public record PathwayHelpDtoRecordView(
        String name,
        String uid,
        String levelName,
        String station,
        String message,
        Date createdAt) {
}
