package com.gnemirko.jobapplier.digest.exception;

/** The call to the scoring API failed: transport error, bad status, redirect, bad JSON or too large an answer. */
public class ScoringApiException extends RuntimeException {

    public ScoringApiException(String message) {
        super(message);
    }

    public ScoringApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
