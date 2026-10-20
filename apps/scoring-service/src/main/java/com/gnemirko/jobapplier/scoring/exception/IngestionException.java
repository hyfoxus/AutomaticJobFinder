package com.gnemirko.jobapplier.scoring.exception;

/** The pull from ingestion failed: transport error, bad status, bad JSON or too large an answer. */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
