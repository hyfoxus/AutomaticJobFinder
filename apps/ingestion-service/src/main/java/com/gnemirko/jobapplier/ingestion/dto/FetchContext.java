package com.gnemirko.jobapplier.ingestion.dto;

import java.util.Set;

/** Per-run input for a source: canonical URLs already stored for it. */
public record FetchContext(Set<String> knownUrls) {

    public static final FetchContext EMPTY = new FetchContext(Set.of());

    public FetchContext {
        knownUrls = knownUrls == null ? Set.of() : knownUrls;
    }
}
