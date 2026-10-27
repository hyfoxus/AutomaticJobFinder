package com.gnemirko.jobapplier.ingestion.learner.exception;

/** The model call failed. Every kind ends the run: a timeout or a 5xx is never retried. */
public class LlmException extends RuntimeException {

    public enum Kind { BUDGET, UNAVAILABLE, TIMEOUT }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
