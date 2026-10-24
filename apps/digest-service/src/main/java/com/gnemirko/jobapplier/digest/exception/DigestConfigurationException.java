package com.gnemirko.jobapplier.digest.exception;

/** A setting is missing or unsafe; stops startup. */
public class DigestConfigurationException extends RuntimeException {

    public DigestConfigurationException(String message) {
        super(message);
    }

    public DigestConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
