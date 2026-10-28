package com.gnemirko.jobapplier.ingestion.learner.dto;

/** Answer of POST /api/clone (202): the outcome goes to {@code <report>.report.txt}, not into this answer. */
public record CloneStarted(String runId, String report) {
}
