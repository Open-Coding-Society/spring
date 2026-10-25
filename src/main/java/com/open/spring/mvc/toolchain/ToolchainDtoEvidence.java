package com.open.spring.mvc.toolchain;

import java.util.List;

/**
 * Advisory AI assessment of the evidence in a submitted Toolchain Trail report.
 *
 * @param status the assessment result: COMPLETE, INCOMPLETE, or REVIEW_NEEDED
 * @param summary a short explanation of the assessment
 * @param missingItems checks reported as missing or failed
 * @param warnings checks that produced warnings
 */
public record ToolchainDtoEvidence(
        String status,
        String summary,
        List<String> missingItems,
        List<String> warnings) {
}
