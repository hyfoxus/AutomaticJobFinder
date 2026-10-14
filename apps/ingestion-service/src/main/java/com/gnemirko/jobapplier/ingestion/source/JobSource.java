package com.gnemirko.jobapplier.ingestion.source;

import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;

public interface JobSource {

    /** Stable source name, stored with every job. */
    String name();

    /** Fetches current postings. Throws on transport failure. Malformed items are dropped and counted. */
    FetchResult fetch(FetchContext context);

    default FetchResult fetch() {
        return fetch(FetchContext.EMPTY);
    }
}
