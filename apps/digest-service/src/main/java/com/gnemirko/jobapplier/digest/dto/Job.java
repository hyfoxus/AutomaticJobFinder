package com.gnemirko.jobapplier.digest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A scored row that passed the checks: it has an id and a score time, a SCORED status and a known tier.
 * Text stays untrusted; {@link com.gnemirko.jobapplier.digest.util.Escape} is the only way to put it into a mail.
 */
public record Job(long id, String title, String company, String location, String url, Tier tier,
                  InterviewChance interviewChance, String reason, List<Gap> gaps, Instant scoredAt) {

    /** A gap with a known kind (an unknown kind counts as HARD, the cautious reading). */
    public record Gap(String text, GapKind kind) {
    }

    /** Empty when the row cannot be used: no id or time, not SCORED, or an unknown tier. */
    public static Optional<Job> from(AssessmentItem i) {
        if (i == null || i.id() == null || i.scoredAt() == null || !"SCORED".equals(i.status())) {
            return Optional.empty();
        }
        Tier tier = parse(Tier.class, i.tier());
        if (tier == null) {
            return Optional.empty();
        }
        List<Gap> gaps = i.gaps() == null ? List.of() : i.gaps().stream()
                .filter(g -> g != null && g.text() != null)
                .map(g -> new Gap(g.text(), parse(GapKind.class, g.kind()) == GapKind.LEARNABLE
                        ? GapKind.LEARNABLE : GapKind.HARD))
                .toList();
        return Optional.of(new Job(i.id(), i.title(), i.company(), i.location(), i.url(), tier,
                parse(InterviewChance.class, i.interviewChance()), i.reason(), gaps, i.scoredAt()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
