package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Instant;

import org.jsoup.nodes.Document;

import com.gnemirko.jobapplier.contracts.JobPosting;

/** Turns one parsed detail page into a posting. A null posting means the page is unusable. */
@FunctionalInterface
interface DetailExtractor {

    /** {@code expired} marks a page that is valid but closed; it is counted apart from bad pages. */
    record Result(JobPosting posting, boolean expired) {
        static final Result BAD = new Result(null, false);
        static final Result EXPIRED = new Result(null, true);
    }

    Result extract(Document page, URI link, Instant updatedAt);
}
