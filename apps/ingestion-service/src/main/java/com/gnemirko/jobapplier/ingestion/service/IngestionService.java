package com.gnemirko.jobapplier.ingestion.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class IngestionService {

    private static final int TOUCH_CHUNK = 1000;

    private final SourceRegistry sources;
    private final JobQueryService queries;
    private final JobRepository jobs;
    private final IngestionRunRepository runs;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HealthEvaluator health;

    public IngestionService(SourceRegistry sources, JobQueryService queries, JobRepository jobs, IngestionRunRepository runs,
            PlatformTransactionManager txManager, Clock clock, HealthEvaluator health) {
        this.sources = sources;
        this.queries = queries;
        this.jobs = jobs;
        this.runs = runs;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.health = health;
    }

    @Scheduled(cron = "${ingestion.cron:0 0 */6 * * *}")
    public void runAll() {
        for (JobSource source : sources.all()) {
            runSource(source);
        }
    }

    /** One source, one transaction. A failure is recorded separately and never stops other sources. */
    public IngestionRunEntity runSource(JobSource source) {
        IngestionRunEntity run = fetchAndStore(source);
        // After the run is stored; the check never throws and a run without an id (unsaved failure) is skipped.
        try {
            health.evaluate(run);
        } catch (RuntimeException e) {
            log.warn("Health check of '{}' failed", source.name(), e);
        }
        return run;
    }

    private IngestionRunEntity fetchAndStore(JobSource source) {
        Instant started = clock.instant();
        try {
            FetchContext context = new FetchContext(queries.knownJobs(source.name()));
            FetchResult result = source.fetch(context);
            return tx.execute(status -> store(source.name(), started, result));
        } catch (FetchBlockedException e) {
            log.error("Ingestion of '{}' blocked: {}", source.name(), e.getMessage());
            return recordFailure(source.name(), started, "blocked");
        } catch (RuntimeException e) {
            log.error("Ingestion of '{}' failed", source.name(), e);
            return recordFailure(source.name(), started, e.toString());
        }
    }

    /** Saving the failure can itself fail (e.g. database down); that must not abort the other sources. */
    private IngestionRunEntity recordFailure(String sourceName, Instant started, String error) {
        IngestionRunEntity run = new IngestionRunEntity(sourceName, started, clock.instant(), Status.FAILED,
                0, 0, 0, 0, error);
        try {
            return runs.save(run);
        } catch (RuntimeException e) {
            log.error("Could not record the failed run of '{}'", sourceName, e);
            return run;
        }
    }

    private IngestionRunEntity store(String sourceName, Instant started, FetchResult result) {
        List<JobPosting> fetched = result.postings();
        // Taken inside the transaction so firstSeenAt reflects store time, not run start.
        Instant now = clock.instant();
        int skipped = result.skipped();
        Map<String, JobPosting> valid = new LinkedHashMap<>();
        for (JobPosting p : fetched) {
            if (!fits(p)) {
                skipped++;
            } else {
                if (valid.putIfAbsent(p.externalId(), p) != null) {
                    // Same id twice in one batch: keep the first, but make the loss visible.
                    skipped++;
                }
            }
        }
        Map<String, JobEntity> existing = new LinkedHashMap<>();
        if (!valid.isEmpty()) {
            for (JobEntity e : jobs.findBySourceAndExternalIdIn(sourceName, valid.keySet())) {
                existing.put(e.getExternalId(), e);
            }
        }
        int inserted = 0;
        int updated = 0;
        List<JobEntity> toSave = new ArrayList<>();
        for (JobPosting p : valid.values()) {
            JobEntity e = existing.get(p.externalId());
            if (e == null) {
                toSave.add(new JobEntity(p, now));
                inserted++;
            } else {
                e.refresh(p, now);
                toSave.add(e);
                updated++;
            }
        }
        jobs.saveAll(toSave);
        // A job that was just re-read is already counted and refreshed; only touch the rest.
        Set<String> toTouch = new LinkedHashSet<>(result.unchangedUrls());
        for (JobPosting p : valid.values()) {
            toTouch.remove(p.url());
        }
        updated += touch(sourceName, toTouch, now);
        IngestionRunEntity run = runs.save(new IngestionRunEntity(sourceName, started, now, Status.SUCCESS,
                fetched.size() + toTouch.size(), inserted, updated, skipped, result.note()));
        log.info("Ingestion of '{}': fetched={} new={} seen={} skipped={}",
                sourceName, fetched.size(), inserted, updated, skipped);
        return run;
    }

    private int touch(String sourceName, Set<String> urls, Instant now) {
        int touched = 0;
        List<String> all = new ArrayList<>(urls);
        for (int i = 0; i < all.size(); i += TOUCH_CHUNK) {
            touched += jobs.touchLastSeen(sourceName, all.subList(i, Math.min(all.size(), i + TOUCH_CHUNK)), now);
        }
        return touched;
    }

    private static boolean fits(JobPosting p) {
        return p != null
                && present(p.externalId(), JobEntity.MAX_EXTERNAL_ID)
                && present(p.title(), JobEntity.MAX_TITLE)
                && present(p.url(), JobEntity.MAX_URL)
                && p.source() != null && p.source().length() <= JobEntity.MAX_SOURCE
                && within(p.company(), JobEntity.MAX_TEXT)
                && within(p.location(), JobEntity.MAX_TEXT)
                && within(p.category(), JobEntity.MAX_CATEGORY)
                && within(p.jobType(), JobEntity.MAX_JOB_TYPE)
                && within(p.salary(), JobEntity.MAX_SALARY);
    }

    private static boolean present(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private static boolean within(String s, int max) {
        return s == null || s.length() <= max;
    }
}
