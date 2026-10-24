package com.gnemirko.jobapplier.digest.dto;

import java.time.Instant;
import java.util.List;

/** One row of {@code GET /api/assessments} as received. Every text field is untrusted. Unknown properties are ignored. */
public record AssessmentItem(
        Long id,
        String title,
        String company,
        String location,
        String url,
        String status,
        String tier,
        List<GapItem> gaps,
        String interviewChance,
        String reason,
        Instant scoredAt) {
}
