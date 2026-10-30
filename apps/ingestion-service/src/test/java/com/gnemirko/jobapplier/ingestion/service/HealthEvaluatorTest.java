package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.entity.SourceHealthEntity;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.repository.SourceHealthRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The recipe health check against a real database: every matrix row from the plan. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthEvaluatorTest {

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    HealthEvaluator evaluator;
    @Autowired IngestionRunRepository runs;
    @Autowired SourceHealthRepository healths;
    final MutableClock clock = new MutableClock();
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SourceRegistry registry;

    /** A clock the tests move by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private static int counter;
    private String src;

    @BeforeEach
    void setUp() {
        src = "health-" + (++counter);
        evaluator = new HealthEvaluator(runs, healths, txManager, clock, 0.3, 3, Duration.ofDays(14));
        Logger logger = (Logger) LoggerFactory.getLogger(HealthEvaluator.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(HealthEvaluator.class)).detachAppender(logs);
    }

    private IngestionRunEntity run(String source, Status status, int inserted, int updated, int skipped, String note,
            Instant finished) {
        IngestionRunEntity r = runs.save(new IngestionRunEntity(source, finished.minusSeconds(5), finished, status,
                inserted + updated, inserted, updated, skipped, note));
        evaluator.evaluate(r);
        return r;
    }

    private void ok(String source, int seen) {
        run(source, Status.SUCCESS, 0, seen, 0, null, clock.instant());
    }

    private void history(String source, int... seen) {
        for (int s : seen) {
            ok(source, s);
        }
    }

    private SourceHealthEntity health(String source) {
        return healths.findById(source).orElseThrow();
    }

    private long infos() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.INFO).count();
    }

    private long warns() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    @Test
    void normalRunStaysHealthy() {
        history(src, 90, 88, 91);
        ok(src, 89);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getBaseline()).isEqualTo(90);
        assertThat(warns()).isZero();
    }

    @Test
    void collapseIsBrokenAndWarnsOnce() {
        history(src, 90, 88, 91);
        clock.advance(Duration.ofMinutes(1));
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        assertThat(health(src).getSince()).isEqualTo(clock.instant());
        assertThat(warns()).isEqualTo(1);
        ok(src, 0);
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        assertThat(health(src).getBaseline()).isEqualTo(90);
        assertThat(warns()).isEqualTo(1);
    }

    @Test
    void dropIsDegradedAndPlateauDoesNotDriftToHealthy() {
        history(src, 90, 88, 91);
        ok(src, 20);
        assertThat(health(src).getState()).isEqualTo("DEGRADED");
        for (int i = 0; i < 6; i++) {
            ok(src, 20);
        }
        assertThat(health(src).getState()).isEqualTo("DEGRADED");
        assertThat(health(src).getBaseline()).isEqualTo(90);
    }

    @Test
    void recoveryResetsSinceAndLogsInfo() {
        history(src, 90, 88, 91);
        clock.advance(Duration.ofMinutes(1));
        ok(src, 0);
        Instant brokenSince = health(src).getSince();
        clock.advance(Duration.ofMinutes(1));
        ok(src, 85);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getSince()).isAfter(brokenSince);
        assertThat(logs.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("improved"))).isTrue();
        assertThat(warns()).isEqualTo(1);
    }

    @Test
    void partialRecoveryStaysDegraded() {
        history(src, 90, 88, 91);
        ok(src, 0);
        assertThat(warns()).isEqualTo(1);
        ok(src, 20);
        assertThat(health(src).getState()).isEqualTo("DEGRADED");
        // BROKEN to DEGRADED is an improvement: INFO, no new WARN
        assertThat(warns()).isEqualTo(1);
        assertThat(infos()).isEqualTo(1);
        ok(src, 80);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(infos()).isEqualTo(2);
    }

    @Test
    void worseningFromDegradedToBrokenWarns() {
        history(src, 90, 88, 91);
        ok(src, 20);
        assertThat(warns()).isEqualTo(1);
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        assertThat(warns()).isEqualTo(2);
    }

    @Test
    void emptyRunDoesNotRecoverABrokenSource() {
        // first-ever run failed: no baseline at all
        run(src, Status.FAILED, 0, 0, 0, "boom", clock.instant());
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        ok(src, 7);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getNote()).isEqualTo("no baseline yet");
    }

    @Test
    void emptyRunDoesNotRecoverSmallFrozenBaseline() {
        history(src, 2, 2, 2);
        run(src, Status.FAILED, 0, 0, 0, "boom", clock.instant());
        assertThat(health(src).getBaseline()).isEqualTo(2);
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("BROKEN");
        ok(src, 2);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
    }

    @Test
    void emptyAndPartialRunsStayOutOfTheBaseline() {
        history(src, 100, 100, 100);
        for (int i = 0; i < 6; i++) {
            run(src, Status.SUCCESS, 1, 0, 0, "Stopped early: run time budget of PT2M reached", clock.instant());
        }
        run(src, Status.SUCCESS, 0, 0, 0, null, clock.instant());
        ok(src, 100);
        assertThat(health(src).getBaseline()).isEqualTo(100);
    }

    @Test
    void steadyHighSkipRecipeStaysHealthyButAJumpIsDegraded() {
        for (int i = 0; i < 3; i++) {
            run(src, Status.SUCCESS, 0, 90, 40, null, clock.instant());
        }
        // quiet day: nothing new, 40 skipped as always
        run(src, Status.SUCCESS, 0, 85, 40, null, clock.instant());
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        // skipped jumps well above three times the usual
        run(src, Status.SUCCESS, 0, 85, 200, null, clock.instant());
        assertThat(health(src).getState()).isEqualTo("DEGRADED");
        assertThat(health(src).getNote()).isEqualTo("detail pages yield no postings");
    }

    @Test
    void tooLittleHistory() {
        history(src, 90, 88);
        ok(src, 89);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getNote()).isEqualTo("no baseline yet");
    }

    @Test
    void firstRunCreatesRow() {
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getNote()).isEqualTo("no baseline yet");
        assertThat(warns()).isZero();
    }

    @Test
    void failedRunIsBrokenWithCappedErrorAndExcludedFromBaseline() {
        history(src, 90, 88, 91);
        run(src, Status.FAILED, 0, 0, 0, "x".repeat(1500), clock.instant());
        SourceHealthEntity h = health(src);
        assertThat(h.getState()).isEqualTo("BROKEN");
        assertThat(h.getNote()).startsWith("run failed: ").hasSizeLessThanOrEqualTo(300);
        assertThat(h.getBaseline()).isEqualTo(90);
    }

    @Test
    void smallSite() {
        history(src, 3, 3, 3);
        ok(src, 2);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
    }

    @Test
    void smallSiteZeroIsBrokenOnlyFromBaselineThree() {
        history(src, 2, 2, 2);
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getNote()).isEqualTo("empty run");
    }

    @Test
    void manyUnchangedSitemapPostingsAreNotDegraded() {
        history(src, 90, 88, 91);
        // All 90 postings unchanged: counted as updated by the runner.
        run(src, Status.SUCCESS, 0, 90, 0, null, clock.instant());
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
    }

    @Test
    void partialRunIsNeverDegradedAndNotInBaseline() {
        history(src, 90, 88, 91);
        run(src, Status.SUCCESS, 10, 0, 0, "Stopped early: run time budget of PT2M reached", clock.instant());
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        run(src, Status.SUCCESS, 5, 0, 0, "1 page(s) disallowed by robots.txt", clock.instant());
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        ok(src, 89);
        assertThat(health(src).getBaseline()).isEqualTo(90);
        // a partial run with zero postings is still broken
        run(src, Status.SUCCESS, 0, 0, 0, "Stopped early: run time budget of PT2M reached", clock.instant());
        assertThat(health(src).getState()).isEqualTo("BROKEN");
    }

    @Test
    void detailParseFailure() {
        history(src, 90, 88, 91);
        run(src, Status.SUCCESS, 0, 4, 60, null, clock.instant());
        assertThat(health(src).getState()).isEqualTo("DEGRADED");
        assertThat(health(src).getNote()).isEqualTo("detail pages yield no postings");
    }

    @Test
    void rampingRecipeStaysHealthy() {
        history(src, 20, 40, 60);
        ok(src, 80);
        ok(src, 100);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
    }

    @Test
    void staleHistoryGivesNoBaseline() {
        Instant old = clock.instant().minus(Duration.ofDays(20));
        for (int s : new int[] {90, 88, 91}) {
            run(src, Status.SUCCESS, 0, s, 0, null, old);
        }
        ok(src, 0);
        assertThat(health(src).getState()).isEqualTo("HEALTHY");
        assertThat(health(src).getNote()).isEqualTo("no baseline yet");
    }

    @Test
    void unsavedFailedRunIsSkipped() {
        IngestionRunEntity unsaved = new IngestionRunEntity(src, clock.instant(), clock.instant(), Status.FAILED, 0, 0, 0,
                0, "db down");
        evaluator.evaluate(unsaved);
        assertThat(healths.findById(src)).isEmpty();
    }

    @Test
    void failingHealthWriteLeavesRunsAndEvaluateReturns() {
        history(src, 90, 88, 91);
        SourceHealthRepository brokenHealths = org.mockito.Mockito.mock(SourceHealthRepository.class);
        org.mockito.Mockito.when(brokenHealths.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(brokenHealths.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db down"));
        HealthEvaluator broken = new HealthEvaluator(runs, brokenHealths, txManager, clock, 0.3, 3, Duration.ofDays(14));
        IngestionRunEntity r = runs.save(new IngestionRunEntity(src, clock.instant(), clock.instant(), Status.SUCCESS, 0,
                0, 0, 0, null));

        broken.evaluate(r);

        // the health write failed and rolled back the run's mark; every stored run is still there
        List<IngestionRunEntity> stored = runs.findBySourceOrderByIdAsc(src);
        assertThat(stored).hasSize(4);
        assertThat(stored.get(3).getHealth()).isNull();
        assertThat(stored.get(0).getHealth()).isEqualTo("HEALTHY");
    }

    @Test
    void endpointListsBrokenRegisteredSourceAndInactiveRows() throws Exception {
        String registered = src + "-live";
        com.gnemirko.jobapplier.ingestion.source.JobSource fake =
                org.mockito.Mockito.mock(com.gnemirko.jobapplier.ingestion.source.JobSource.class);
        org.mockito.Mockito.when(fake.name()).thenReturn(registered);
        org.mockito.Mockito.doReturn(List.of(fake)).when(registry).all();
        history(registered, 90, 88, 91);
        ok(registered, 0);
        ok(src, 5);

        String body = mvc.perform(get("/api/health/sources")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).contains("\"source\":\"" + registered + "\",\"state\":\"BROKEN\"");
        assertThat(body).contains("\"source\":\"" + src + "\",\"state\":\"INACTIVE\"");
        assertThat(warns()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void endpointRejectsForeignHostAndEncodedPaths() throws Exception {
        for (String path : List.of("/api/health/sources", "/api/%48ealth/sources", "/api/./health/sources",
                "/API/Health/sources", "/api//health/sources")) {
            mvc.perform(get(java.net.URI.create(path)).with(r -> {
                r.setServerName("evil.example.com");
                return r;
            })).andExpect(status().isForbidden()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get("/api/health/sources").with(r -> {
            r.setServerName("127.0.0.1");
            return r;
        })).andExpect(status().isOk());
    }
}
