package com.gnemirko.jobapplier.ingestion.learner.exception;

/** A learn run is already active; maps to 409. */
public class LearnBusyException extends RuntimeException {

    public LearnBusyException() {
        super("a learn run is already active");
    }
}
