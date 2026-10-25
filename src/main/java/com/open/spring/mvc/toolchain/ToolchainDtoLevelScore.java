package com.open.spring.mvc.toolchain;

/**
 * Data Transfer Object (DTO) representing the body of a student's performance score 
 * for a specific CS Pathway level.
 *
 * @param levelKey       The identifier for the CS Pathway level.
 * @param levelName      The human-readable name of the level.
 * @param percentComplete The score achieved, represented as a percentage (0.0 to 100.0).
 * @param finished       Indicates whether the student has completed this level.
 */
public record ToolchainDtoLevelScore(
        String levelKey,
        String levelName,
        double percentComplete,
        boolean finished) {
}
