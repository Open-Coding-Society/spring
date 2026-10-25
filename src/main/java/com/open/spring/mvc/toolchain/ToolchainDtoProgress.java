package com.open.spring.mvc.toolchain;

/**
 * Progress update accepted by PUT /api/cs-pathway/scores/{levelKey}.
 *
 * @param percentComplete the reported completion percentage from 0 through 100
 */
public record ToolchainDtoProgress(
    Double percentComplete) {
}
