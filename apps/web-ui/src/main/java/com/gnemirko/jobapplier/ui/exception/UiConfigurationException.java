package com.gnemirko.jobapplier.ui.exception;

/** A UI setting is unusable; the application refuses to start. */
public class UiConfigurationException extends RuntimeException {

    public UiConfigurationException(String message) {
        super(message);
    }
}
