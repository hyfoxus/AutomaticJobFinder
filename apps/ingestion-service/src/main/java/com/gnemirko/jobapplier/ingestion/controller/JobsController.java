package com.gnemirko.jobapplier.ingestion.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.ingestion.service.JobQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobsController {

    private final JobQueryService jobs;

    /** Jobs first seen strictly after {@code since} (default: epoch), oldest first. */
    @GetMapping
    public List<JobRecord> since(@RequestParam(defaultValue = "1970-01-01T00:00:00Z") Instant since) {
        return jobs.since(since);
    }
}
