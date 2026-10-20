package com.gnemirko.jobapplier.scoring.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.dto.ScoringSummary;
import com.gnemirko.jobapplier.scoring.entity.IngestCursorEntity;
import com.gnemirko.jobapplier.scoring.repository.IngestCursorRepository;
import com.gnemirko.jobapplier.scoring.source.IngestionClient;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * One pull-and-score run: read the cursor, pull from ingestion, score in chunks, move the cursor.
 * Only one run is active at a time; the scheduler and the manual trigger share the same guard.
 * The cursor moves only after {@code score(chunk)} returned, to the largest firstSeenAt of that chunk.
 */
@Slf4j
@Service
@ScoringEnabled
public class ScoringRunner {

    private static final Instant EPOCH = Instant.EPOCH;

    private final IngestionClient client;
    private final ScoringService scoring;
    private final IngestCursorRepository cursors;
    private final ScoringProperties props;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scoring-run");
        t.setDaemon(true);
        return t;
    });

    public ScoringRunner(IngestionClient client, ScoringService scoring, IngestCursorRepository cursors,
                         ScoringProperties props, Clock clock) {
        props.ingestion().validate();
        this.client = client;
        this.scoring = scoring;
        this.cursors = cursors;
        this.props = props;
        this.clock = clock;
    }

    /** Runs in the caller's thread. Returns false, doing nothing, when a run is already active. */
    public boolean runNow() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            runOnce();
        } finally {
            running.set(false);
        }
        return true;
    }

    /** Starts a run in the background. Returns false when a run is already active. */
    public boolean startAsync() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    runOnce();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("A scoring run did not finish within 30s of shutdown; interrupting it");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Never throws: a failure is logged and the next tick tries again. */
    private void runOnce() {
        try {
            Instant cursor = cursors.findById(IngestCursorEntity.ID).map(IngestCursorEntity::getLastFirstSeenAt).orElse(null);
            Instant since = cursor == null ? EPOCH : cursor.minus(props.ingestion().overlap());

            List<JobRecord> pulled;
            try {
                pulled = client.fetchSince(since);
            } catch (Exception e) {
                log.warn("Pull from ingestion failed, cursor unchanged: {}", e.getMessage(), e);
                pulled = List.of();
            }

            if (pulled.isEmpty()) {
                logSummary("retry of stored rows", scoring.score(List.of()));
                return;
            }
            int size = props.ingestion().chunkSize();
            List<JobRecord> all = new ArrayList<>(pulled);
            for (int from = 0; from < all.size(); from += size) {
                List<JobRecord> chunk = all.subList(from, Math.min(all.size(), from + size));
                ScoringSummary summary = scoring.score(new ArrayList<>(chunk));
                Instant max = maxFirstSeen(chunk);
                if (max != null) {
                    cursors.advance(max, clock.instant());
                }
                logSummary("chunk " + (from / size + 1), summary);
                if (summary.stopReason() != null) {
                    log.warn("Run stopped early: {}. {} job(s) of the pull were not sent.", summary.stopReason(),
                            all.size() - from - chunk.size());
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Scoring run failed: {}", e.toString(), e);
        }
    }

    private static Instant maxFirstSeen(List<JobRecord> chunk) {
        Instant max = null;
        for (JobRecord r : chunk) {
            if (r != null && r.firstSeenAt() != null && (max == null || r.firstSeenAt().isAfter(max))) {
                max = r.firstSeenAt();
            }
        }
        return max;
    }

    private static void logSummary(String what, ScoringSummary s) {
        log.info("Scoring {}: received={} stored={} scored={} rejected={} failed={} unscoredBudget={} spentToday={}µ$ state={}",
                what, s.received(), s.stored(), s.scored(), s.rejectedByFilter(), s.failed(), s.unscoredBudget(),
                s.spentTodayMicros(), s.budgetState());
    }
}
