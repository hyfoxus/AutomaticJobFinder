package com.gnemirko.jobapplier.digest.exception;

/** The digest could not be handed over (SMTP refused or unreachable, or the dry-run file could not be written). */
public class DeliveryException extends RuntimeException {

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
