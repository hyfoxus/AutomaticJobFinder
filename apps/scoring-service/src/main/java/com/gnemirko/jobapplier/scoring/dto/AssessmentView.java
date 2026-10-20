package com.gnemirko.jobapplier.scoring.dto;

import java.time.Instant;
import java.util.List;

import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;

/**
 * What the API shows of a stored row. The description is never included. The reason is model or filter
 * text: it is shown only for scored and filtered rows, capped, and must be escaped by whoever renders it.
 */
public record AssessmentView(
        Long id,
        String title,
        String company,
        String location,
        String url,
        AssessmentStatus status,
        Tier tier,
        List<Integer> metMustHaves,
        List<Gap> gaps,
        InterviewChance interviewChance,
        String reason,
        int attempts,
        Instant firstSeenAt,
        Instant createdAt,
        Instant updatedAt,
        Instant scoredAt) {

    static final int MAX_REASON = 500;
    static final int MAX_GAP_TEXT = 300;
    static final int MAX_GAPS = 20;

    public static AssessmentView of(AssessmentEntity e) {
        List<Gap> gaps = e.getGaps() == null ? null : e.getGaps().stream()
                .limit(MAX_GAPS)
                .map(g -> new Gap(cap(g.text(), MAX_GAP_TEXT), g.kind()))
                .toList();
        return new AssessmentView(e.getId(), e.getTitle(), e.getCompany(), e.getLocation(), e.getUrl(), e.getStatus(),
                e.getTier(), e.getMetMustHaves(), gaps, e.getInterviewChance(), reasonFor(e), e.getAttempts(),
                e.getFirstSeenAt(), e.getCreatedAt(), e.getUpdatedAt(), e.getScoredAt());
    }

    private static String reasonFor(AssessmentEntity e) {
        return switch (e.getStatus()) {
            case SCORED, REJECTED_FILTER -> cap(e.getReason(), MAX_REASON);
            case PENDING -> "Waiting to be scored.";
            case FAILED -> "Scoring failed; it will be tried again.";
            case FAILED_PERMANENT -> "Scoring failed and will not be tried again.";
            case UNSCORED_BUDGET -> "Left unscored because the daily budget was reached.";
        };
    }

    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
