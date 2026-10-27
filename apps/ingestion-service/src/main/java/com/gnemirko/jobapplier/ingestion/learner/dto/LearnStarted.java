package com.gnemirko.jobapplier.ingestion.learner.dto;

/** Answer of POST /api/learn (202): the result goes to a file named after {@code name}, not into this answer. */
public record LearnStarted(String runId, String name) {
}
