package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.entity.IngestCursorEntity;
import com.gnemirko.jobapplier.scoring.service.ScoringRunner;

class ScoringRunnerTest extends WiringTestBase {

    static final Instant BASE = Instant.parse("2026-10-19T08:00:00Z");

    @Autowired ScoringRunner runner;
    @Autowired com.gnemirko.jobapplier.scoring.source.IngestionClient client;

    @Test
    void oversizedBodyIsRejectedWithTheSizeLimitInTheMessage() {
        for (StubIngestion.Mode mode : List.of(StubIngestion.Mode.HUGE, StubIngestion.Mode.HUGE_CHUNKED)) {
            STUB.mode = mode;
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.fetchSince(Instant.EPOCH))
                    .isInstanceOf(com.gnemirko.jobapplier.scoring.exception.IngestionException.class)
                    .hasMessageContaining("size limit");
            runner.runNow();
            assertThat(assessments.count()).isZero();
            assertThat(cursor()).isNull();
        }
    }

    @Test
    void slowAnswerTimesOutQuicklyEvenWithRealJobs() {
        STUB.mode = StubIngestion.Mode.SLOW;
        STUB.jobs = many(3);
        long start = System.nanoTime();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.fetchSince(Instant.EPOCH))
                .isInstanceOf(com.gnemirko.jobapplier.scoring.exception.IngestionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void secondPullWithNewJobsAdvancesTheCursor() {
        STUB.jobs = many(3);
        runner.runNow();
        assertThat(cursor()).isEqualTo(BASE.plusSeconds(2));

        List<JobRecord> more = new ArrayList<>(many(3));
        more.add(at("new1", BASE.plusSeconds(500)));
        more.add(at("new2", BASE.plusSeconds(600)));
        STUB.jobs = more;
        runner.runNow();

        assertThat(assessments.count()).isEqualTo(5);
        assertThat(cursor()).isEqualTo(BASE.plusSeconds(600));
    }

    static JobRecord at(String id, Instant seen) {
        return new JobRecord(new JobPosting(id, "test", "Java Developer", "Acme", "Remote, Europe",
                "https://example.com/" + id, "Spring Boot.", null, null, null, null, null), seen);
    }

    static List<JobRecord> many(int n) {
        List<JobRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(at("j" + i, BASE.plusSeconds(i)));
        }
        return out;
    }

    Instant cursor() {
        return cursors.findById(IngestCursorEntity.ID).map(IngestCursorEntity::getLastFirstSeenAt).orElse(null);
    }

    @Test
    void firstRunScoresEverythingInChunksThenSecondRunAddsNothing() {
        STUB.jobs = many(120);

        assertThat(runner.runNow()).isTrue();

        assertThat(assessments.count()).isEqualTo(120);
        assertThat(assessments.findAll()).allMatch(a -> a.getStatus() == AssessmentStatus.SCORED);
        assertThat(cursor()).isEqualTo(BASE.plusSeconds(119));
        assertThat(STUB.sinces).containsExactly("1970-01-01T00:00:00Z");
        assertThat(model.calls()).isEqualTo(120);

        assertThat(runner.runNow()).isTrue();

        assertThat(Instant.parse(STUB.sinces.get(1))).isEqualTo(BASE.plusSeconds(119).minus(Duration.ofMinutes(5)));
        assertThat(assessments.count()).isEqualTo(120);
        assertThat(model.calls()).isEqualTo(120);
        assertThat(cursor()).isEqualTo(BASE.plusSeconds(119));
    }

    @Test
    void equalTimestampsAcrossAChunkBoundaryCreateNoDuplicates() {
        List<JobRecord> same = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            same.add(at("s" + i, BASE));
        }
        STUB.jobs = same;

        runner.runNow();
        runner.runNow();

        assertThat(assessments.count()).isEqualTo(51);
        assertThat(cursor()).isEqualTo(BASE);
        assertThat(model.calls()).isEqualTo(51);
    }

    @Test
    void emptyPullRetriesStoredRowsAndLeavesTheCursor() {
        model.fail(new RuntimeException("boom"));
        scoring.score(jobs("a"));
        assertThat(row("a").getStatus()).isEqualTo(AssessmentStatus.FAILED);
        Instant before = BASE;
        cursors.advance(before, BASE);

        runner.runNow();

        assertThat(row("a").getStatus()).isEqualTo(AssessmentStatus.SCORED);
        assertThat(cursor()).isEqualTo(before);
    }

    @Test
    void cursorNeverMovesBackwards() {
        cursors.advance(BASE.plusSeconds(100), BASE);
        cursors.advance(BASE, BASE);
        assertThat(cursor()).isEqualTo(BASE.plusSeconds(100));
    }

    @Test
    void ingestionErrorBadJsonTimeoutAndOversizeLeaveTheCursorAndStillRetryStoredRows() {
        for (StubIngestion.Mode mode : List.of(StubIngestion.Mode.ERROR, StubIngestion.Mode.BAD_JSON,
                StubIngestion.Mode.SLOW, StubIngestion.Mode.HUGE, StubIngestion.Mode.HUGE_CHUNKED)) {
            assessments.deleteAll();
            cursors.deleteAll();
            model.reset();
            model.fail(new RuntimeException("boom"));
            scoring.score(jobs("a"));
            cursors.advance(BASE, BASE);
            STUB.mode = mode;
            STUB.jobs = many(3); // real jobs after the cursor: none may be stored from a failed pull

            assertThat(runner.runNow()).isTrue();

            assertThat(assessments.count()).as(mode.name()).isEqualTo(1);

            assertThat(cursor()).as(mode.name()).isEqualTo(BASE);
            assertThat(row("a").getStatus()).as(mode.name()).isEqualTo(AssessmentStatus.SCORED);
        }
    }

    @Test
    void secondRunIsRefusedWhileOneIsActive() throws Exception {
        STUB.jobs = many(1);
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.onCall = p -> {
            inCall.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        assertThat(runner.startAsync()).isTrue();
        assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.isRunning()).isTrue();
        assertThat(runner.startAsync()).isFalse();
        assertThat(runner.runNow()).isFalse();

        release.countDown();
        waitUntilIdle();
        assertThat(runner.runNow()).isTrue();
    }

    void waitUntilIdle() throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (runner.isRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(runner.isRunning()).isFalse();
    }
}
