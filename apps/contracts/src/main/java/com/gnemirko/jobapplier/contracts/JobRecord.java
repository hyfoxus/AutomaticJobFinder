package com.gnemirko.jobapplier.contracts;

import java.time.Instant;

/**
 * A stored vacancy as returned by the pull API. A consumer advances its cursor
 * to the maximum firstSeenAt it received and sends it back as {@code since}.
 */
public record JobRecord(JobPosting posting, Instant firstSeenAt) {
}
