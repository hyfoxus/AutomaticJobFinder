package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.gnemirko.jobapplier.digest.dto.RunResult;

/** The very first run looks back digest.initial-lookback (7 days) from the clock. */
@Import({TestcontainersConfiguration.class, DigestLookbackTest.FixedClock.class})
class DigestLookbackTest extends DigestFlowTestBase {

    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Test
    void firstRunStartsSevenDaysBack() throws Exception {
        STUB.items = List.of(
                StubScoring.item(1, "STRONG", "LIKELY", NOW.minus(Duration.ofDays(8))),
                StubScoring.item(2, "STRONG", "LIKELY", NOW.minus(Duration.ofDays(6))));

        RunResult r = runner.runNow();

        assertThat(r.included()).isEqualTo(1);
        assertThat(URLDecoder.decode(STUB.queries.get(0), StandardCharsets.UTF_8))
                .contains("scoredSince=" + NOW.minus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("select assessment_id from digest.sent_assessment", Long.class)).isEqualTo(2L);
    }
}
