package com.gnemirko.jobapplier.scoring.exception;

/** The learner token is missing or wrong; shown to the caller as HTTP 403. */
public class LearnForbiddenException extends RuntimeException {

    public LearnForbiddenException() {
        super("forbidden");
    }
}
