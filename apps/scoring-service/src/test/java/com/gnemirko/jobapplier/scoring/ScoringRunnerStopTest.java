package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpClientErrorException;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.entity.IngestCursorEntity;
import com.gnemirko.jobapplier.scoring.repository.IngestCursorRepository;
import com.gnemirko.jobapplier.scoring.service.ScoringRunner;
import com.gnemirko.jobapplier.scoring.service.ScoringService;

/** Chunks of two jobs, roomy budget: what happens to the cursor when a run ends between chunks. */
@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.max-tokens=1000",
        "scoring.llm.prices[test-model].input-per-million=0.01",
        "scoring.llm.prices[test-model].output-per-million=0.01",
        "scoring.budget.daily-usd=10.00",
        "scoring.budget.zone=UTC",
        "scoring.ingestion.cron=-",
        "scoring.ingestion.chunk-size=2"
})
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
class ScoringRunnerStopTest extends ScoringTestSupport {

    static final StubIngestion STUB = new StubIngestion();
    static final Instant T = ScoringRunnerTest.BASE;

    @Autowired ScoringRunner runner;
    @Autowired IngestCursorRepository cursors;
    @MockitoSpyBean ScoringService scoringSpy;

    @DynamicPropertySource
    static void ingestion(DynamicPropertyRegistry registry) {
        registry.add("scoring.ingestion.base-url", STUB::baseUrl);
    }

    Instant cursor() {
        return cursors.findById(IngestCursorEntity.ID).map(IngestCursorEntity::getLastFirstSeenAt).orElse(null);
    }

    private void fresh(List<JobRecord> jobs) {
        cursors.deleteAll();
        STUB.reset();
        STUB.jobs = jobs;
    }

    private static HttpClientErrorException unauthorized() {
        return HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "no", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void authFailureOnChunkOneSendsNoLaterChunkAndCursorIsChunkOnesMax() {
        fresh(ScoringRunnerTest.many(6));
        model.fail(unauthorized());

        runner.runNow();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(assessments.count()).isEqualTo(2); // chunks 2 and 3 were never sent
        assertThat(cursor()).isEqualTo(T.plusSeconds(1));
    }

    @Test
    void cursorStaysAtChunkOneWhenScoreThrowsOnChunkTwo() {
        fresh(ScoringRunnerTest.many(6));
        Mockito.doCallRealMethod().doThrow(new IllegalStateException("db down")).when(scoringSpy).score(anyList());

        assertThatCode(() -> runner.runNow()).doesNotThrowAnyException();

        assertThat(cursor()).isEqualTo(T.plusSeconds(1));
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void equalTimestampsStraddlingAChunkBoundaryAreNotLostWhenTheRunStopsBetweenChunks() {
        Instant u = T.plusSeconds(10);
        fresh(List.of(ScoringRunnerTest.at("a", T), ScoringRunnerTest.at("b", T),
                ScoringRunnerTest.at("c", u), ScoringRunnerTest.at("d", u),
                ScoringRunnerTest.at("e", u), ScoringRunnerTest.at("f", u.plusSeconds(5))));
        // chunk 1 (two calls) succeeds, chunk 2 hits an auth failure and the run stops
        model.replyValid().replyValid().fail(unauthorized());

        runner.runNow();

        assertThat(cursor()).isEqualTo(u);
        assertThat(assessments.count()).isEqualTo(4); // "e" and "f" not yet seen

        model.reset();
        runner.runNow(); // pulls since u - overlap, so c, d, e are re-sent; duplicates are absorbed

        assertThat(assessments.count()).isEqualTo(6);
        assertThat(assessments.findAll()).allMatch(a -> a.getStatus() == AssessmentStatus.SCORED);
        assertThat(cursor()).isEqualTo(u.plusSeconds(5));
    }
}
