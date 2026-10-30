package com.gnemirko.jobapplier.ingestion.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.entity.SourceHealthEntity;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.repository.SourceHealthRepository;
import com.gnemirko.jobapplier.ingestion.source.RunNotes;
import lombok.extern.slf4j.Slf4j;

/**
 * Judges a finished source run from the {@code ingestion_runs} history only (no fetches, no model). The measure is
 * {@code seen = inserted + updated}; unchanged sitemap postings are already counted in {@code updated}. The baseline is
 * the median of earlier healthy, complete successful runs and stays frozen while a source is not HEALTHY.
 */
@Service
@Slf4j
public class HealthEvaluator {

    public static final String HEALTHY = "HEALTHY";
    public static final String DEGRADED = "DEGRADED";
    public static final String BROKEN = "BROKEN";

    static final int HISTORY = 5;
    static final int MIN_RUNS = 3;
    static final int MIN_BASELINE_DEGRADED = 10;

    private final IngestionRunRepository runs;
    private final SourceHealthRepository healths;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final double ratio;
    private final int minBaselineBroken;
    private final Duration maxAge;

    public HealthEvaluator(IngestionRunRepository runs, SourceHealthRepository healths,
            PlatformTransactionManager txManager, Clock clock,
            @Value("${ingestion.health.degraded-ratio:0.3}") double ratio,
            @Value("${ingestion.health.min-baseline-broken:3}") int minBaselineBroken,
            @Value("${ingestion.health.baseline-max-age:14d}") Duration maxAge) {
        this.runs = runs;
        this.healths = healths;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.ratio = ratio;
        this.minBaselineBroken = minBaselineBroken;
        this.maxAge = maxAge;
    }

    /** Never throws: a health failure must not affect stored jobs or other sources. */
    public void evaluate(IngestionRunEntity run) {
        if (run == null || run.getId() == null) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> judge(run));
        } catch (RuntimeException e) {
            log.warn("Health check of '{}' failed", run.getSource(), e);
        }
    }

    private void judge(IngestionRunEntity run) {
        String source = run.getSource();
        Instant now = clock.instant();
        Optional<SourceHealthEntity> previous = healths.findById(source);
        String prevState = previous.map(SourceHealthEntity::getState).orElse(null);
        boolean down = prevState != null && !HEALTHY.equals(prevState);
        boolean frozen = down && previous.get().getBaseline() != null;
        History history = history(run, now);
        Integer baseline = frozen ? previous.get().getBaseline() : history.median();

        int seen = run.getInserted() + run.getUpdated();
        String state;
        String note = null;
        if (run.getStatus() == Status.FAILED) {
            state = BROKEN;
            note = "run failed: " + oneLine(run.getError());
            if (baseline == null) {
                baseline = previous.map(SourceHealthEntity::getBaseline).orElse(null);
            }
            seen = 0;
        } else {
            boolean partial = RunNotes.isPartial(run.getError());
            int total = seen + run.getSkipped();
            // A source that is down recovers only on a real run: postings seen, and at the threshold of the frozen baseline.
            boolean recovered = !down || (seen > 0 && (baseline == null || seen >= ratio * baseline));
            if (!recovered) {
                if (seen == 0) {
                    state = BROKEN;
                    note = "no postings (baseline " + baseline + ")";
                } else {
                    state = baseline != null && baseline >= MIN_BASELINE_DEGRADED && !partial ? DEGRADED : prevState;
                    note = "below the recovery threshold of baseline " + baseline;
                }
            } else if (seen == 0 && baseline != null && baseline >= minBaselineBroken) {
                state = BROKEN;
                note = "no postings (baseline " + baseline + ")";
            } else if (seen == 0 && baseline != null) {
                state = HEALTHY;
                note = "empty run";
            } else if (seen > 0 && run.getInserted() == 0 && !partial
                    && run.getSkipped() >= Math.max(5, total / 2.0)
                    && run.getSkipped() >= 3 * Math.max(1, history.lastSkipped())) {
                state = DEGRADED;
                note = "detail pages yield no postings";
            } else if (baseline == null) {
                state = HEALTHY;
                note = "no baseline yet";
            } else if (baseline >= MIN_BASELINE_DEGRADED && seen < ratio * baseline) {
                if (partial) {
                    // A cut-off run is not evidence of a decline; keep the previous verdict.
                    state = prevState == null ? HEALTHY : prevState;
                    note = "partial run";
                } else {
                    state = DEGRADED;
                    note = "saw " + seen + ", baseline " + baseline;
                }
            } else {
                state = HEALTHY;
            }
        }

        run.markHealth(state);
        runs.save(run);
        SourceHealthEntity row = previous.orElse(null);
        if (row == null) {
            row = new SourceHealthEntity(source, state, now, seen, baseline, note);
        } else {
            row.apply(state, now, seen, baseline, note);
        }
        healths.save(row);

        if (!state.equals(prevState)) {
            if (prevState == null && HEALTHY.equals(state)) {
                return;
            }
            if (prevState != null && rank(state) < rank(prevState)) {
                log.info("Source '{}' improved: now {} (was {}; saw {}, baseline {})", source, state, prevState, seen,
                        baseline);
            } else {
                log.warn("Source '{}' is {}: {} (was {})", source, state, note, prevState == null ? "new" : prevState);
            }
        }
    }

    private static int rank(String state) {
        return HEALTHY.equals(state) ? 0 : DEGRADED.equals(state) ? 1 : 2;
    }

    /** Median of the last runs that count (null when fewer than three exist) and the skipped count of the latest one. */
    private record History(Integer median, int lastSkipped) {
    }

    private History history(IngestionRunEntity run, Instant now) {
        // The query already leaves out unhealthy, empty and partial runs.
        List<IngestionRunEntity> candidates = runs.baselineCandidates(run.getSource(), run.getId(), now.minus(maxAge),
                PageRequest.of(0, HISTORY));
        int lastSkipped = candidates.isEmpty() ? 0 : candidates.get(0).getSkipped();
        int[] values = candidates.stream().mapToInt(r -> r.getInserted() + r.getUpdated()).sorted().toArray();
        if (values.length < MIN_RUNS) {
            return new History(null, lastSkipped);
        }
        int n = values.length;
        return new History(n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2, lastSkipped);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "unknown error";
        }
        String clean = text.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 250));
    }
}
