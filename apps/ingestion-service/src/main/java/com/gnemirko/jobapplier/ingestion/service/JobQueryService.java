package com.gnemirko.jobapplier.ingestion.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;

@Service
public class JobQueryService {

    private final JobRepository jobs;

    public JobQueryService(JobRepository jobs) {
        this.jobs = jobs;
    }

    /** Jobs first seen strictly after {@code since}, oldest first. */
    @Transactional(readOnly = true)
    public List<JobRecord> since(Instant since) {
        return jobs.findFirstSeenAfter(since).stream().map(JobEntity::toRecord).toList();
    }

    /** Canonical urls already stored for a source. Note: this is a per-source scan of the jobs table. */
    @Transactional(readOnly = true)
    public Set<String> knownUrls(String source) {
        return new HashSet<>(jobs.findUrlsBySource(source));
    }
}
