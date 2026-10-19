package com.gnemirko.jobapplier.scoring.exception;

/** Startup refuses to continue: the message says what to fix. */
public class ScoringConfigurationException extends RuntimeException {

    public ScoringConfigurationException(String message) {
        super(message);
    }

    public ScoringConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
