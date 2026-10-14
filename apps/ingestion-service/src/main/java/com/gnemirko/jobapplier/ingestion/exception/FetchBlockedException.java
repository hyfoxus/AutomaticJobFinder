package com.gnemirko.jobapplier.ingestion.exception;

/** The host refused automated access (403, 429 or a bot challenge). Never retried past. */
public class FetchBlockedException extends FetchException {

    public FetchBlockedException(String message) {
        super(message);
    }
}
