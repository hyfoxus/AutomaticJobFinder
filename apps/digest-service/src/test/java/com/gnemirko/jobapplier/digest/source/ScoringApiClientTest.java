package com.gnemirko.jobapplier.digest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.digest.StubScoring;
import com.gnemirko.jobapplier.digest.config.DigestProperties;
import com.gnemirko.jobapplier.digest.dto.Tier;
import com.gnemirko.jobapplier.digest.exception.ScoringApiException;

class ScoringApiClientTest {

    private final StubScoring stub = new StubScoring();

    private ScoringApiClient client(long maxBytes) {
        DigestProperties props = new DigestProperties(true, DigestProperties.Mode.DRY_RUN, "out",
                List.of(Tier.STRONG), 50, Duration.ofMinutes(5), Duration.ofDays(7), "-",
                new DigestProperties.Scoring(stub.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2), maxBytes),
                new DigestProperties.Smtp(null, 587, null, null, List.of(), false, false));
        return new ScoringApiClient(props);
    }

    @AfterEach
    void reset() {
        stub.reset();
    }

    @Test
    void declaredOversizeIsRefused() {
        stub.mode = StubScoring.Mode.HUGE;
        assertThatThrownBy(() -> client(100_000).fetchScored(Instant.EPOCH, 0, 200))
                .isInstanceOf(ScoringApiException.class).hasMessageContaining("size limit");
    }

    @Test
    void chunkedOversizeIsStoppedByTheStreamCap() {
        stub.mode = StubScoring.Mode.HUGE_CHUNKED;
        assertThatThrownBy(() -> client(100_000).fetchScored(Instant.EPOCH, 0, 200))
                .isInstanceOf(ScoringApiException.class).hasMessageContaining("size limit");
    }

    @Test
    void redirectBadJsonAndErrorsAreFailures() {
        for (StubScoring.Mode mode : List.of(StubScoring.Mode.REDIRECT, StubScoring.Mode.BAD_JSON, StubScoring.Mode.ERROR)) {
            stub.mode = mode;
            assertThatThrownBy(() -> client(100_000).fetchScored(Instant.EPOCH, 0, 200)).as(mode.name())
                    .isInstanceOf(ScoringApiException.class);
        }
    }

    @Test
    void normalAnswerIsRead() {
        stub.items = List.of(StubScoring.item(1, "STRONG", "LIKELY", Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(client(100_000).fetchScored(Instant.EPOCH, 0, 200)).hasSize(1);
        assertThat(client(100_000).fetchUsage().calls()).isEqualTo(7L);
    }
}
