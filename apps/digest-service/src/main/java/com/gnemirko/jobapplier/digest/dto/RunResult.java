package com.gnemirko.jobapplier.digest.dto;

/** What one run did. {@code waiting} is how many qualifying jobs were left for the next run. */
public record RunResult(Outcome outcome, int included, int waiting) {

    public enum Outcome { SENT, DRY_RUN, NOTHING_NEW, SCORING_UNAVAILABLE, DELIVERY_FAILED, RECORD_FAILED, BUSY }

    public static RunResult of(Outcome outcome) {
        return new RunResult(outcome, 0, 0);
    }
}
