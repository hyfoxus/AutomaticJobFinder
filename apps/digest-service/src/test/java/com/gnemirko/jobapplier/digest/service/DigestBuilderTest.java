package com.gnemirko.jobapplier.digest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.dto.GapItem;
import com.gnemirko.jobapplier.digest.dto.Job;
import com.gnemirko.jobapplier.digest.dto.UsageInfo;

class DigestBuilderTest {

    private final DigestBuilder builder = new DigestBuilder();

    private static Job job(String title, String url, String reason, List<GapItem> gaps) {
        return Job.from(new AssessmentItem(1L, title, "Acme <co>", "Berlin", url, "SCORED", "strong", gaps, "likely",
                reason, Instant.parse("2026-10-25T10:00:00Z"))).orElseThrow();
    }

    @Test
    void hostileTextIsOnlyEverEscapedText() {
        Job j = job("<script>alert(1)</script>", "javascript:alert(1)", "r</p><script>x()</script>\r\nSubject: x",
                List.of(new GapItem("<img src=x onerror=y>", "HARD")));

        Digest d = builder.build(UUID.randomUUID(), List.of(j), 0, null);

        assertThat(d.html()).doesNotContain("<script").doesNotContain("<img").doesNotContain("<a ")
                .doesNotContain("javascript:").contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("Acme &lt;co&gt;");
        assertThat(d.text()).doesNotContain("javascript:");
        assertThat(d.text().lines().filter(l -> l.startsWith("Subject:"))).isEmpty();
        assertThat(d.subject()).isEqualTo("JobApplier digest: 1 new jobs (1 strong, 0 partial, 0 weak)");
    }

    @Test
    void safeUrlBecomesAHardenedLinkAndNoImagesAppear() {
        Job j = job("Dev", "https://example.com/jobs/1?a=b&c='d'", "ok", List.of());

        Digest d = builder.build(UUID.randomUUID(), List.of(j), 0, null);

        assertThat(d.html()).contains("href=\"https://example.com/jobs/1?a=b&amp;c=&#39;d&#39;\"")
                .contains("rel=\"noopener noreferrer\"").doesNotContain("<img").doesNotContain("src=");
        assertThat(d.text()).contains("https://example.com/jobs/1");
    }

    @Test
    void gapsAreSeparatedByKindAndTheChanceIsLabelledHeuristic() {
        Job j = job("Dev", "https://example.com/1", "ok",
                List.of(new GapItem("Kubernetes", "LEARNABLE"), new GapItem("Visa", "HARD")));

        Digest d = builder.build(UUID.randomUUID(), List.of(j), 3, null);

        assertThat(d.html()).contains("Hard gaps: Visa").contains("Learnable gaps: Kubernetes")
                .contains("Interview chance (heuristic): LIKELY").contains("3 more waiting");
        assertThat(d.text()).contains("Hard gaps: Visa").contains("Learnable gaps: Kubernetes")
                .contains("Interview chance (heuristic): LIKELY");
    }

    @Test
    void usageLineIsLastAndOmittedWithoutUsage() {
        Job j = job("Dev", "https://example.com/1", "ok", List.of());

        Digest with = builder.build(UUID.randomUUID(), List.of(j), 0, new UsageInfo("0.5", "1.0", "OK", 4L));
        Digest without = builder.build(UUID.randomUUID(), List.of(j), 0, null);

        assertThat(with.text().stripTrailing()).endsWith("Scoring spend today: $0.5 of $1.0 (4 calls, budget OK)");
        assertThat(without.text()).doesNotContain("Scoring spend");
        assertThat(without.html()).doesNotContain("Scoring spend");
    }

    @Test
    void unknownTierOrStatusRowsAreSkipped() {
        assertThat(Job.from(new AssessmentItem(1L, "t", "c", "l", "u", "SCORED", "AMAZING", null, null, "r", Instant.now())))
                .isEmpty();
        assertThat(Job.from(new AssessmentItem(1L, "t", "c", "l", "u", "FAILED", "STRONG", null, null, "r", Instant.now())))
                .isEmpty();
        assertThat(Job.from(new AssessmentItem(null, "t", "c", "l", "u", "SCORED", "STRONG", null, null, "r", Instant.now())))
                .isEmpty();
    }
}
