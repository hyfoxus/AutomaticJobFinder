package com.gnemirko.jobapplier.scoring.exception;

/** A request field is over its cap; shown to the caller as HTTP 413. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
