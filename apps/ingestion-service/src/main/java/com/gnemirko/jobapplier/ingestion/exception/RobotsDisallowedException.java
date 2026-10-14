package com.gnemirko.jobapplier.ingestion.exception;

/** robots.txt forbids the requested path for this client. */
public class RobotsDisallowedException extends FetchException {

    public RobotsDisallowedException(String message) {
        super(message);
    }
}
