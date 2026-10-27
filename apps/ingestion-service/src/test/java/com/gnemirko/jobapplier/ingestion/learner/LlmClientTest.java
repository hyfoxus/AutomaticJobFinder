package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.Completion;
import com.gnemirko.jobapplier.ingestion.learner.exception.LlmException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LlmException.Kind;
import com.gnemirko.jobapplier.ingestion.learner.source.LlmClient;

class LlmClientTest {

    @TempDir Path tmp;

    private LearnerProperties props(StubScoring s, Path token, Duration read, long maxBytes) {
        return new LearnerProperties(true, List.of(), 12000, s.baseUrl(), token.toString(), tmp.toString(),
                Duration.ofSeconds(2), read, maxBytes, Duration.ofMinutes(1));
    }

    private Path token(String value) throws Exception {
        Path t = tmp.resolve("token");
        Files.writeString(t, value);
        return t;
    }

    @Test
    void sendsTokenAndRunHeaderAndReadsTheReply() throws Exception {
        try (StubScoring s = new StubScoring()) {
            s.reply("{\"strategy\":\"none\"}");
            Completion c = new LlmClient(props(s, token("0123456789abcdef\n"), Duration.ofSeconds(5), 65536))
                    .complete("a.example.com", "skeleton", null);
            assertThat(c.text()).isEqualTo("{\"strategy\":\"none\"}");
            assertThat(c.finishReason()).isEqualTo("STOP");
            assertThat(s.requests.get(0).token()).isEqualTo("0123456789abcdef");
            assertThat(s.requests.get(0).run()).isEqualTo("1");
            assertThat(s.requests.get(0).json().get("host").asString()).isEqualTo("a.example.com");
        }
    }

    @Test
    void mapsStatusesAndNeverRetries() throws Exception {
        try (StubScoring s = new StubScoring()) {
            LlmClient c = new LlmClient(props(s, token("0123456789abcdef"), Duration.ofSeconds(5), 65536));
            int[] statuses = {402, 429, 504, 502, 500, 404, 403};
            Kind[] kinds = {Kind.BUDGET, Kind.BUDGET, Kind.TIMEOUT, Kind.UNAVAILABLE, Kind.UNAVAILABLE,
                    Kind.UNAVAILABLE, Kind.UNAVAILABLE};
            for (int i = 0; i < statuses.length; i++) {
                s.replyRaw(statuses[i], "{\"error\":\"x\"}");
                Kind expected = kinds[i];
                assertThatThrownBy(() -> c.complete("h", "s", null))
                        .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(expected));
            }
            assertThat(s.requests).hasSize(statuses.length);
        }
    }

    @Test
    void readTimeoutIsATimeoutAndIsNotRetried() throws Exception {
        try (StubScoring s = new StubScoring()) {
            s.replySlow("x", 2000);
            LlmClient c = new LlmClient(props(s, token("0123456789abcdef"), Duration.ofMillis(300), 65536));
            assertThatThrownBy(() -> c.complete("h", "s", null))
                    .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
            assertThat(s.requests).hasSize(1);
        }
    }

    @Test
    void oversizedAndMalformedAnswersAreUnavailable() throws Exception {
        try (StubScoring s = new StubScoring()) {
            LlmClient c = new LlmClient(props(s, token("0123456789abcdef"), Duration.ofSeconds(5), 50));
            s.reply("x".repeat(500));
            assertThatThrownBy(() -> c.complete("h", "s", null)).isInstanceOf(LlmException.class);
            LlmClient big = new LlmClient(props(s, token("0123456789abcdef"), Duration.ofSeconds(5), 65536));
            s.replyRaw(200, "not json");
            assertThatThrownBy(() -> big.complete("h", "s", null)).isInstanceOf(LlmException.class);
            s.replyRaw(200, "{\"finishReason\":\"STOP\"}");
            assertThatThrownBy(() -> big.complete("h", "s", null)).isInstanceOf(LlmException.class);
        }
    }

    @Test
    void missingOrShortTokenFailsAtStartup() throws Exception {
        try (StubScoring s = new StubScoring()) {
            assertThatThrownBy(() -> new LlmClient(props(s, tmp.resolve("nope"), Duration.ofSeconds(1), 100)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new LlmClient(props(s, token("short"), Duration.ofSeconds(1), 100)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
