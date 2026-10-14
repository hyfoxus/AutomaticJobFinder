package com.gnemirko.jobapplier.ingestion.exception;

/** A fetch failed, or was refused by policy. Treat as transient unless a subclass says otherwise. */
public class FetchException extends RuntimeException {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
