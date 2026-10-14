package com.gnemirko.jobapplier.ingestion.run;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.store.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.store.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.store.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.store.JobEntity;
import com.gnemirko.jobapplier.ingestion.store.JobRepository;

@Component
public class IngestionRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final List<JobSource> sources;
    private final JobRepository jobs;
    private final IngestionRunRepository runs;
    private final TransactionTemplate tx;
    private final Clock clock;

    public IngestionRunner(List<JobSource> sources, JobRepository jobs, IngestionRunRepository runs,
            PlatformTransactionManager txManager, Clock clock) {
        this.sources = sources;
        this.jobs = jobs;
        this.runs = runs;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${ingestion.cron:0 0 */6 * * *}")
    public void runAll() {
        for (JobSource source : sources) {
            runSource(source);
        }
    }

    /** One source, one transaction. A failure is recorded separately and never stops other sources. */
    public IngestionRunEntity runSource(JobSource source) {
        Instant started = clock.instant();
        try {
            List<JobPosting> fetched = source.fetch();
            int malformed = source.skippedInLastFetch();
            return tx.execute(status -> store(source.name(), started, fetched, malformed));
        } catch (RuntimeException e) {
            log.error("Ingestion of '{}' failed", source.name(), e);
            return runs.save(new IngestionRunEntity(source.name(), started, clock.instant(), Status.FAILED,
                    0, 0, 0, 0, e.toString()));
        }
    }

    private IngestionRunEntity store(String sourceName, Instant started, List<JobPosting> fetched, int malformed) {
        // Taken inside the transaction so firstSeenAt reflects store time, not run start.
        Instant now = clock.instant();
        int skipped = malformed;
        Map<String, JobPosting> valid = new LinkedHashMap<>();
        for (JobPosting p : fetched) {
            if (!fits(p)) {
                skipped++;
            } else {
                valid.putIfAbsent(p.externalId(), p);
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
        IngestionRunEntity run = runs.save(new IngestionRunEntity(sourceName, started, now, Status.SUCCESS,
                fetched.size(), inserted, updated, skipped, null));
        log.info("Ingestion of '{}': fetched={} new={} seen={} skipped={}",
                sourceName, fetched.size(), inserted, updated, skipped);
        return run;
    }

    private static boolean fits(JobPosting p) {
        return p != null
                && present(p.externalId(), JobEntity.MAX_EXTERNAL_ID)
                && present(p.title(), JobEntity.MAX_TITLE)
                && present(p.url(), JobEntity.MAX_URL)
                && p.source() != null && p.source().length() <= JobEntity.MAX_SOURCE
                && within(p.company(), JobEntity.MAX_TEXT)
                && within(p.location(), JobEntity.MAX_TEXT);
    }

    private static boolean present(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private static boolean within(String s, int max) {
        return s == null || s.length() <= max;
    }
}
