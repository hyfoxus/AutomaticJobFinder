package com.gnemirko.jobapplier.ui.exception;

/** A request parameter is outside the whitelist. The page answers 400 with fixed text. */
public class BadParameterException extends RuntimeException {

    public BadParameterException(String message) {
        super(message);
    }
}
