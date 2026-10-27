package com.gnemirko.jobapplier.ingestion.learner.exception;

/** The request is not acceptable (bad URL, host not on the allow-list); maps to 400. */
public class LearnRejectedException extends RuntimeException {

    public LearnRejectedException(String message) {
        super(message);
    }
}
