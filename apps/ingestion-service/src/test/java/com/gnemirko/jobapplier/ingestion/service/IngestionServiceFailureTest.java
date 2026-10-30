package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;
import com.gnemirko.jobapplier.ingestion.source.JobSource;

/** A database outage while recording a failed run must not stop the remaining sources. */
class IngestionServiceFailureTest {

    private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    private final JobQueryService queries = mock(JobQueryService.class);
    private final SourceRegistry registry = mock(SourceRegistry.class);

    private final HealthEvaluator health = mock(HealthEvaluator.class);

    private IngestionService service() {
        return new IngestionService(registry, queries, mock(JobRepository.class), runs,
                mock(PlatformTransactionManager.class), Clock.systemUTC(), health);
    }

    private static JobSource failing(String name) {
        JobSource s = mock(JobSource.class);
        when(s.name()).thenReturn(name);
        when(s.fetch(any(FetchContext.class))).thenThrow(new IllegalStateException("source down"));
        return s;
    }

    @Test
    void failureToRecordTheRunDoesNotAbortOtherSources() {
        JobSource first = failing("first");
        JobSource second = failing("second");
        when(registry.all()).thenReturn(List.of(first, second));
        when(queries.knownJobs(any())).thenReturn(Map.of());
        when(runs.save(any())).thenThrow(new IllegalStateException("database down"));

        service().runAll();

        verify(first).fetch(any(FetchContext.class));
        verify(second).fetch(any(FetchContext.class));
    }

    @Test
    void unsavedFailureIsStillReturnedAsFailed() {
        JobSource only = failing("only");
        when(queries.knownJobs(any())).thenReturn(Map.of());
        when(runs.save(any())).thenThrow(new IllegalStateException("database down"));

        IngestionRunEntity run = service().runSource(only);

        assertThat(run.getStatus()).isEqualTo(Status.FAILED);
        assertThat(run.getError()).contains("source down");
    }

    private static JobSource empty(String name) {
        JobSource s = mock(JobSource.class);
        when(s.name()).thenReturn(name);
        when(s.fetch(any(FetchContext.class))).thenReturn(
                new com.gnemirko.jobapplier.ingestion.dto.FetchResult(List.of(), 0, java.util.Set.of(), null));
        return s;
    }

    @Test
    void healthIsEvaluatedAfterTheRunWithTheSavedRun() {
        when(queries.knownJobs(any())).thenReturn(Map.of());
        when(runs.save(any())).thenAnswer(i -> i.getArgument(0));

        IngestionRunEntity run = service().runSource(empty("ok"));

        verify(health).evaluate(run);
        assertThat(run.getStatus()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void failingEvaluatorDoesNotStopRunAll() {
        JobSource first = empty("first");
        JobSource second = empty("second");
        when(registry.all()).thenReturn(List.of(first, second));
        when(queries.knownJobs(any())).thenReturn(Map.of());
        when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("health down")).when(health).evaluate(any());

        service().runAll();

        verify(second).fetch(any(FetchContext.class));
    }

    @Test
    void runWithoutIdIsSkippedByTheEvaluator() {
        com.gnemirko.jobapplier.ingestion.repository.SourceHealthRepository healths =
                mock(com.gnemirko.jobapplier.ingestion.repository.SourceHealthRepository.class);
        HealthEvaluator real = new HealthEvaluator(runs, healths, mock(PlatformTransactionManager.class),
                Clock.systemUTC(), 0.3, 3, java.time.Duration.ofDays(14));
        IngestionService svc = new IngestionService(registry, queries, mock(JobRepository.class), runs,
                mock(PlatformTransactionManager.class), Clock.systemUTC(), real);
        when(queries.knownJobs(any())).thenReturn(Map.of());
        when(runs.save(any())).thenThrow(new IllegalStateException("database down"));

        IngestionRunEntity run = svc.runSource(failing("nosave"));

        assertThat(run.getId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(healths);
    }
}
