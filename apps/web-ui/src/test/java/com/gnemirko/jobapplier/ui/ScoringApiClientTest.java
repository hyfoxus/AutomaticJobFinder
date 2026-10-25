package com.gnemirko.jobapplier.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ui.config.UiProperties;
import com.gnemirko.jobapplier.ui.dto.AssessmentPage;
import com.gnemirko.jobapplier.ui.dto.AssessmentStatus;
import com.gnemirko.jobapplier.ui.dto.RunOutcome;
import com.gnemirko.jobapplier.ui.dto.Tier;
import com.gnemirko.jobapplier.ui.exception.ScoringApiException;
import com.gnemirko.jobapplier.ui.source.ScoringApiClient;

/** The client against a real loopback stub: sizes, redirects, timeouts and shapes. */
class ScoringApiClientTest {

    static final StubScoring STUB = new StubScoring();
    ScoringApiClient client;

    @BeforeEach
    void setUp() {
        STUB.reset();
        client = new ScoringApiClient(new UiProperties(true, java.util.List.of("localhost"), Duration.ZERO,
                new UiProperties.Scoring(STUB.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1), 10_000)));
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Test
    void readsRowsWithUnknownFieldsAndUnknownEnumsAsNull() {
        STUB.assessments.body = "[{\"id\":1,\"tier\":\"MEGA\",\"status\":\"SCORED\",\"extra\":true,\"interviewChance\":\"LIKELY\","
                + "\"gaps\":[{\"text\":\"a\",\"kind\":\"???\"}]}]";
        STUB.assessments.hasMore = "true";
        AssessmentPage page = client.fetchAssessments(AssessmentStatus.SCORED, Tier.STRONG, 2, 50);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().tier()).isNull();
        assertThat(page.items().getFirst().status()).isEqualTo(AssessmentStatus.SCORED);
        assertThat(page.items().getFirst().gaps().getFirst().kind()).isNull();
        assertThat(STUB.queries.getFirst()).isEqualTo("limit=50&page=2&status=SCORED&tier=STRONG");
    }

    @Test
    void oversizeBodyWithoutDeclaredLengthAndWithIsRefused() {
        STUB.assessments.raw = ("[" + "1,".repeat(20_000) + "1]").getBytes();
        assertThatThrownBy(() -> client.fetchAssessments(AssessmentStatus.SCORED, null, 0, 50))
                .isInstanceOf(ScoringApiException.class).hasMessageContaining("size limit");
    }

    @Test
    void chunkedOversizeBodyIsRefused() {
        STUB.assessments.chunked = true;
        STUB.assessments.raw = ("[" + "{\"id\":1},".repeat(3000) + "{\"id\":1}]").getBytes();
        assertThatThrownBy(() -> client.fetchAssessments(AssessmentStatus.SCORED, null, 0, 50))
                .isInstanceOf(ScoringApiException.class).hasMessageContaining("size limit");
    }

    @Test
    void bodyThatStallsAfterTheHeadersIsCutAtTheDeadline() {
        STUB.assessments.body = "[{\"id\":1}]";
        STUB.assessments.stallAfterBytes = 3;
        STUB.assessments.stallMs = 8000;
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.fetchAssessments(AssessmentStatus.SCORED, null, 0, 50))
                .isInstanceOf(ScoringApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void redirectsAreNotFollowed() {
        STUB.usage.status = 301;
        STUB.usage.location = STUB.baseUrl() + "/api/assessments";
        assertThatThrownBy(() -> client.fetchUsage()).isInstanceOf(ScoringApiException.class).hasMessageContaining("301");
        assertThat(STUB.assessmentCalls.get()).isZero();
    }

    @Test
    void badJsonAndWrongShapeAreRefusedWithoutTheText() {
        STUB.usage.body = "SECRET {not json";
        assertThatThrownBy(() -> client.fetchUsage()).isInstanceOf(ScoringApiException.class)
                .hasMessageNotContaining("SECRET");
        STUB.assessments.body = "{\"not\":\"a list\"}";
        assertThatThrownBy(() -> client.fetchAssessments(AssessmentStatus.SCORED, null, 0, 50))
                .isInstanceOf(ScoringApiException.class);
        STUB.usage.body = "null";
        assertThatThrownBy(() -> client.fetchUsage()).isInstanceOf(ScoringApiException.class);
    }

    @Test
    void timeoutIsAnError() {
        STUB.usage.delayMs = 2500;
        assertThatThrownBy(() -> client.fetchUsage()).isInstanceOf(ScoringApiException.class);
    }

    @Test
    void connectionRefusedIsAnError() {
        ScoringApiClient dead = new ScoringApiClient(new UiProperties(true, java.util.List.of("localhost"), Duration.ZERO,
                new UiProperties.Scoring("http://127.0.0.1:1", Duration.ofSeconds(1), Duration.ofSeconds(1), 1000)));
        assertThatThrownBy(dead::fetchUsage).isInstanceOf(ScoringApiException.class);
        assertThatThrownBy(dead::startRun).isInstanceOf(ScoringApiException.class);
    }

    @Test
    void runOutcomes() {
        assertThat(client.startRun()).isEqualTo(RunOutcome.STARTED);
        STUB.run.status = 409;
        assertThat(client.startRun()).isEqualTo(RunOutcome.BUSY);
        STUB.run.status = 400;
        assertThatThrownBy(client::startRun).isInstanceOf(ScoringApiException.class);
        assertThat(STUB.lastRunMarker).isEqualTo("1");
    }
}
