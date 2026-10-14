package com.gnemirko.jobapplier.contracts;

import java.time.Instant;

/** A vacancy as published by a source. Immutable; postedAt may be null. */
public record JobPosting(
        String externalId,
        String source,
        String title,
        String company,
        String location,
        String url,
        String description,
        Instant postedAt) {
}
