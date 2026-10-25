package com.gnemirko.jobapplier.ui.dto;

import java.time.Instant;
import java.util.List;

/** One row of {@code GET /api/assessments} as received. Every text field is untrusted. Unknown properties are ignored. */
public record AssessmentItem(
        Long id,
        String title,
        String company,
        String location,
        String url,
        AssessmentStatus status,
        Tier tier,
        List<Integer> metMustHaves,
        List<GapItem> gaps,
        InterviewChance interviewChance,
        String reason,
        Instant firstSeenAt,
        Instant scoredAt) {
}
