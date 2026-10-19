package com.gnemirko.jobapplier.scoring.dto;

/**
 * Outcome of one model call.
 *
 * @param kind          what happened
 * @param assessment    set only for {@link Kind#VALID}
 * @param inputTokens   tokens to charge for the prompt (estimated when the provider gave none)
 * @param outputTokens  tokens to charge for the reply
 * @param estimated     true when either side was estimated
 * @param charged       false when nothing is to be charged (failure before any tokens were used)
 * @param error         short description for logs, never shown to the model
 */
public record CallResult(
        Kind kind,
        Assessment assessment,
        long inputTokens,
        long outputTokens,
        boolean estimated,
        boolean charged,
        String error) {

    public enum Kind { VALID, INVALID, TIMEOUT, PROVIDER_FAILURE, AUTH_FAILURE, REQUEST_REJECTED }
}
