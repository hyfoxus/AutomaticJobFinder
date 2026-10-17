package com.gnemirko.jobapplier.contracts;

import java.time.Instant;

/**
 * A vacancy as published by a source. Immutable; postedAt, category, jobType, salary and updatedAt
 * may be null. updatedAt is the source's own last-modified marker (for example a sitemap lastmod).
 */
public record JobPosting(
        String externalId,
        String source,
        String title,
        String company,
        String location,
        String url,
        String description,
        Instant postedAt,
        String category,
        String jobType,
        String salary,
        Instant updatedAt) {
}
