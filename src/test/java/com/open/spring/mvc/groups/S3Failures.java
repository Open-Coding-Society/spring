package com.open.spring.mvc.groups;

import java.util.List;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** The S3 failures the group chat tests throw from a stand-in S3 client. */
final class S3Failures {

    private S3Failures() {
    }

    /**
     * S3 throttled (503 Slow Down), out of reach, or timing out the request (its RequestTimeout
     * comes as a 400): failures that clear if you wait.
     */
    static List<RuntimeException> unavailable() {
        return List.of(
                (S3Exception) S3Exception.builder().statusCode(503).message("Slow Down").build(),
                SdkClientException.create("Unable to execute HTTP request: Read timed out"),
                (S3Exception) S3Exception.builder().statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestTimeout").build())
                        .message("Your socket connection to the server was not read from or written to within the timeout period.")
                        .build());
    }

    /** No permission, a bad key, or a fault in this server: waiting won't fix these. */
    static List<RuntimeException> refusalsAndBugs() {
        return List.of(
                (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build(),
                new NullPointerException("a bug here, not S3"));
    }
}
