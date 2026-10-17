package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Instant;

import org.jsoup.nodes.Document;

import com.gnemirko.jobapplier.contracts.JobPosting;

/** The embedded-json extractor: maps a JSON script block onto a posting. */
final class EmbeddedJsonExtractor implements DetailExtractor {

    private final String sourceName;
    private final EmbeddedJsonParser parser;

    EmbeddedJsonExtractor(String sourceName, EmbeddedJsonParser parser) {
        this.sourceName = sourceName;
        this.parser = parser;
    }

    @Override
    public Result extract(Document doc, URI link, Instant updatedAt) {
        EmbeddedJsonParser.Outcome out = parser.parse(doc);
        return switch (out.kind()) {
            case EXPIRED -> Result.EXPIRED;
            case BAD -> Result.BAD;
            case OK -> {
                EmbeddedJsonParser.Parsed p = out.parsed();
                yield new Result(new JobPosting(p.id(), sourceName, p.title(), p.company(), p.location(),
                        link.toString(), p.description(), p.postedAt(), p.category(), p.jobType(), null, updatedAt),
                        false);
            }
        };
    }
}
