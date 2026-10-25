package com.gnemirko.jobapplier.ui.dto;

/** What a click on the run button came to. The name is the fixed {@code msg} value on the redirect. */
public enum RunOutcome {
    STARTED("started"), BUSY("busy"), FAILED("failed");

    private final String code;

    RunOutcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
