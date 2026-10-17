package com.gnemirko.jobapplier.ingestion.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-run input for a source: canonical URL of every job already stored for it, mapped to the
 * stored source marker ({@code updatedAt}), which may be null.
 */
public record FetchContext(Map<String, Instant> knownJobs) {

    public static final FetchContext EMPTY = new FetchContext(Map.of());

    public FetchContext {
        knownJobs = knownJobs == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(knownJobs));
    }

    public Set<String> knownUrls() {
        return knownJobs.keySet();
    }
}
