package com.gnemirko.jobapplier.scoring;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;
import com.gnemirko.jobapplier.scoring.repository.AssessmentRepository;
import com.gnemirko.jobapplier.scoring.repository.LlmUsageRepository;
import com.gnemirko.jobapplier.scoring.service.ScoringService;

/** Shared plumbing for the database-backed tests. */
public abstract class ScoringTestSupport {

    @Autowired protected ScoringService scoring;
    @Autowired protected AssessmentRepository assessments;
    @Autowired protected LlmUsageRepository usage;
    @Autowired protected FakeChatModel model;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanState() {
        assessments.deleteAll();
        usage.deleteAll();
        model.reset();
        clock.set(Instant.parse("2026-10-19T10:00:00Z"));
    }

    protected static JobRecord job(String id) {
        return job(id, "Java Developer", "Acme", "Remote, Europe", "Build services with Spring Boot.", 0);
    }

    protected static JobRecord job(String id, String title, String company, String location, String description,
                                   int secondsAgo) {
        Instant seen = Instant.parse("2026-10-19T09:00:00Z").minusSeconds(secondsAgo);
        return new JobRecord(new JobPosting(id, "test", title, company, location, "https://example.com/" + id,
                description, null, null, null, null, null), seen);
    }

    protected AssessmentEntity row(String id) {
        return assessments.findBySourceAndExternalId("test", id).orElseThrow();
    }

    protected static List<JobRecord> jobs(String... ids) {
        // The first id is the newest, so it is also the first to be processed.
        java.util.List<JobRecord> out = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            out.add(job(ids[i], "Java Developer", "Acme", "Remote, Europe", "Build services with Spring Boot.", i));
        }
        return out;
    }
}
