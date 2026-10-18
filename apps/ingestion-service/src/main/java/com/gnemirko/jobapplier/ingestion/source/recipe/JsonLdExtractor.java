package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;

import com.gnemirko.jobapplier.contracts.JobPosting;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** The json-ld extractor: first schema.org JobPosting on the page; id from the page or the URL. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class JsonLdExtractor implements DetailExtractor {

    private static final long MATCH_LIMIT_NANOS = Duration.ofMillis(100).toNanos();

    private final String sourceName;
    /** Null to take the id from the page's own identifier. */
    private final Pattern idPattern;
    private boolean timeoutWarned;

    @Override
    public Result extract(Document doc, URI link, Instant updatedAt) {
        Optional<JsonLdJobPostingParser.Parsed> parsed = JsonLdJobPostingParser.parse(doc);
        if (parsed.isEmpty()) {
            return Result.BAD;
        }
        JsonLdJobPostingParser.Parsed p = parsed.get();
        String id = idPattern == null ? p.identifier() : idFromUrl(link.toString());
        if (id == null || id.isBlank() || p.title() == null) {
            return Result.BAD;
        }
        return new Result(new JobPosting(id.strip(), sourceName, p.title(), p.company(), p.location(),
                link.toString(), p.description(), p.postedAt(), null, p.jobType(), null, updatedAt), false);
    }

    private String idFromUrl(String url) {
        try {
            Matcher m = idPattern.matcher(TimeLimitedCharSequence.limited(url, MATCH_LIMIT_NANOS));
            return m.find() ? m.group(1) : null;
        } catch (TimeLimitedCharSequence.MatchTimeoutException e) {
            if (!timeoutWarned) {
                timeoutWarned = true;
                log.warn("Recipe '{}': a regex match timed out and was treated as no match; check the recipe patterns",
                        sourceName);
            }
            return null;
        }
    }
}
