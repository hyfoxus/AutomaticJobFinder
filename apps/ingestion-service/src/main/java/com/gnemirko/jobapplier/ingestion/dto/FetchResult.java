package com.gnemirko.jobapplier.ingestion.dto;

import java.util.List;
import java.util.Set;

import com.gnemirko.jobapplier.contracts.JobPosting;

/**
 * Outcome of one source fetch. {@code unchangedUrls} are already-stored jobs the source saw but
 * did not re-download; the runner only refreshes their lastSeenAt. {@code note} is an optional
 * remark stored with a successful run (for example an early stop).
 */
public record FetchResult(List<JobPosting> postings, int skipped, Set<String> unchangedUrls, String note) {

    public FetchResult {
        postings = postings == null ? List.of() : List.copyOf(postings);
        unchangedUrls = unchangedUrls == null ? Set.of() : Set.copyOf(unchangedUrls);
    }

    public static FetchResult of(List<JobPosting> postings, int skipped) {
        return new FetchResult(postings, skipped, Set.of(), null);
    }
}
