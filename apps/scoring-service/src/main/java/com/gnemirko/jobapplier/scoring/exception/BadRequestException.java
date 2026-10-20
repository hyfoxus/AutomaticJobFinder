package com.gnemirko.jobapplier.scoring.exception;

/** A request parameter is unusable; shown to the caller as HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
