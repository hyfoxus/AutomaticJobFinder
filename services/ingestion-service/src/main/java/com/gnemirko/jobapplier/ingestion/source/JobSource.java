package com.gnemirko.jobapplier.ingestion.source;

import java.util.List;

import com.gnemirko.jobapplier.contracts.JobPosting;

public interface JobSource {

    /** Stable source name, stored with every job. */
    String name();

    /** Fetches current postings. Throws on transport failure. Malformed items are dropped. */
    List<JobPosting> fetch();

    /** Number of malformed items dropped by the most recent {@link #fetch()}. */
    default int skippedInLastFetch() {
        return 0;
    }
}
