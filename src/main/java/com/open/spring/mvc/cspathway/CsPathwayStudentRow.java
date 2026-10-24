package com.open.spring.mvc.cspathway;

import java.util.List;

/**
 * Represents a single row in the teacher's dashboard view, summarizing a student's 
 * performance across all CS Pathway levels.
 *
 * @param uid             The unique identifier of the student.
 * @param name            The display name of the student.
 * @param levels          A list of {@link CsPathwayLevelScore} objects, representing 
 *                        the student's score for each individual level.
 * @param averagePercent  The calculated average percentage score across all levels.
 */
public record CsPathwayStudentRow(
        String uid,
        String name,
        List<CsPathwayLevelScore> levels,
        double averagePercent) {
}
