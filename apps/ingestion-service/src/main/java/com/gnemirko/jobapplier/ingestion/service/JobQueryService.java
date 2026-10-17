package com.gnemirko.jobapplier.ingestion.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Canonical url to stored source marker (possibly null) for a source. Note: this is a per-source
     * scan of the jobs table. A url stored more than once maps to null so it is always refetched.
     */
    @Transactional(readOnly = true)
    public Map<String, Instant> knownJobs(String source) {
        Map<String, Instant> result = new HashMap<>();
        for (Object[] row : jobs.findUrlMarkersBySource(source)) {
            String url = (String) row[0];
            Instant marker = (Instant) row[1];
            if (result.containsKey(url)) {
                result.put(url, null);
            } else {
                result.put(url, marker);
            }
        }
        return result;
    }
}
