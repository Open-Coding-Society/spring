package com.open.spring.mvc.cspathway;

/**
 * Progress update accepted by PUT /api/cs-pathway/scores/{levelKey}.
 *
 * @param percentComplete the reported completion percentage from 0 through 100
 */
public record PathwayDtoProgress(
    Double percentComplete) {
}
