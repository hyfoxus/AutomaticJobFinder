package com.gnemirko.jobapplier.scoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.gnemirko.jobapplier.scoring.config.ApiProperties;
import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** scoredSince on GET /api/assessments, and the host allow-list. */
class ScoringSinceTest extends WiringTestBase {

    @Autowired MockMvc mvc;

    private void scoreAt(String id, String instant) {
        clock.set(Instant.parse(instant));
        scoring.score(java.util.List.of(job(id)));
    }

    @Test
    void scoredSinceIsOldestScoredFirstAndKeysetOnEqualInstants() throws Exception {
        scoreAt("a", "2026-10-19T10:00:00Z");
        scoreAt("b", "2026-10-19T10:00:00Z");
        scoreAt("c", "2026-10-19T10:05:00Z");
        long aId = row("a").getId();

        mvc.perform(get("/api/assessments?status=SCORED&scoredSince=2026-10-19T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].url").value("https://example.com/a"))
                .andExpect(jsonPath("$[1].url").value("https://example.com/b"))
                .andExpect(jsonPath("$[2].url").value("https://example.com/c"));
        // Same instant, id after a's: b and c remain.
        mvc.perform(get("/api/assessments?scoredSince=2026-10-19T10:00:00Z&afterId=" + aId + "&limit=1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://example.com/b"));
        mvc.perform(get("/api/assessments?scoredSince=2026-10-19T10:00:00.000001Z"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://example.com/c"));
        // Nanosecond input is cut to microseconds.
        mvc.perform(get("/api/assessments?scoredSince=2026-10-19T10:05:00.0000009Z&afterId=0"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void scoredSinceOnlyReturnsScoredRows() throws Exception {
        model.fail(new RuntimeException("x"));
        scoring.score(java.util.List.of(job("f")));
        mvc.perform(get("/api/assessments?scoredSince=1970-01-01T00:00:00Z")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void badParametersAre400() throws Exception {
        mvc.perform(get("/api/assessments?scoredSince=yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?scoredSince=1970-01-01T00:00:00Z&afterId=x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?scoredSince=1970-01-01T00:00:00Z&afterId=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?scoredSince=1970-01-01T00:00:00Z&status=FAILED")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?afterId=3")).andExpect(status().isBadRequest());
    }

    @Test
    void configuredHostIsServedAndOthersAreNot() throws Exception {
        mvc.perform(get("/api/usage/today").header("Host", "scoring-service:8082")).andExpect(status().isOk());
        mvc.perform(get("/api/usage/today").header("Host", "SCORING-SERVICE")).andExpect(status().isOk());
        mvc.perform(get("/api/usage/today").header("Host", "evil.example")).andExpect(status().isForbidden());
    }

    @Test
    void allowedHostsAreValidated() {
        assertThat(new ApiProperties(java.util.List.of("LocalHost", " scoring-service ")).normalizedHosts())
                .containsExactly("localhost", "scoring-service");
        for (java.util.List<String> bad : java.util.List.of(java.util.List.<String>of(), java.util.List.of(" "),
                java.util.List.of("*"), java.util.List.of("a:8082"), java.util.List.of("http://a"))) {
            assertThatThrownBy(() -> new ApiProperties(bad).normalizedHosts())
                    .isInstanceOf(ScoringConfigurationException.class);
        }
    }
}
