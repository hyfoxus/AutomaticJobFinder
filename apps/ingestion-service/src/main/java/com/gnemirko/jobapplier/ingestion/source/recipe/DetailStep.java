package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import lombok.extern.slf4j.Slf4j;

/**
 * One run's worth of detail fetching, shared by both strategies: fetches a link, hands the page to
 * the extractor and keeps the counters. Five consecutive failed fetches abort the run; a blocked
 * response propagates at once.
 */
@Slf4j
final class DetailStep {

    /** Turns the body of a successfully fetched page into a result; {@code link} is the public url. */
    @FunctionalInterface
    interface BodyReader {
        DetailExtractor.Result read(FetchedPage page, URI link, Instant updatedAt);
    }

    static final int MAX_CONSECUTIVE_DETAIL_FAILURES = 5;
    private static final Pattern CHARSET_PARAM = Pattern.compile("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", Pattern.CASE_INSENSITIVE);

    private final Function<URI, FetchedPage> fetch;
    private final String sourceName;
    private final BodyReader reader;
    private final int cap;
    private int detailFetches;
    private int failStreak;
    private int skipped;
    private int expired;
    private int failed;
    private int bad;
    private int robotsSkipped;
    private final java.util.List<String> skipReasons = new java.util.ArrayList<>();

    /** At most this many "url: reason" entries are kept for diagnostics. */
    static final int MAX_SKIP_REASONS = 10;

    /** The HTML path: pages fetched as-is and parsed with jsoup for the extractor. */
    DetailStep(PoliteFetcher fetcher, String sourceName, DetailExtractor extractor, int cap) {
        this(fetcher::fetch, sourceName, htmlReader(extractor), cap);
    }

    /** Pluggable path: any fetch function and body reader. */
    DetailStep(Function<URI, FetchedPage> fetch, String sourceName, BodyReader reader, int cap) {
        this.fetch = fetch;
        this.sourceName = sourceName;
        this.reader = reader;
        this.cap = cap;
    }

    private static BodyReader htmlReader(DetailExtractor extractor) {
        return (page, link, updatedAt) -> {
            Document doc;
            try {
                doc = Jsoup.parse(new ByteArrayInputStream(page.body()), charsetOf(page.contentType()),
                        page.finalUri().toString());
            } catch (IOException e) {
                return DetailExtractor.Result.BAD;
            }
            return extractor.extract(doc, link, updatedAt);
        };
    }

    /** The extractor a recipe asks for. */
    static DetailExtractor extractorFor(Recipe recipe, Pattern idPattern) {
        if (Recipe.EXTRACTOR_EMBEDDED_JSON.equals(recipe.detail().extractor())) {
            return new EmbeddedJsonExtractor(recipe.name(), new EmbeddedJsonParser(recipe.detail().embeddedJson()));
        }
        return new JsonLdExtractor(recipe.name(), idPattern);
    }

    boolean capReached() {
        return detailFetches >= cap;
    }

    int detailFetches() {
        return detailFetches;
    }

    /** Bounded list of "host/path?query: category" for pages that were skipped; diagnostics only. */
    java.util.List<String> skipReasons() {
        return java.util.List.copyOf(skipReasons);
    }

    private void why(URI link, String reason) {
        if (skipReasons.size() < MAX_SKIP_REASONS) {
            String q = link.getRawQuery();
            skipReasons.add(link.getHost() + (link.getRawPath() == null ? "" : link.getRawPath())
                    + (q == null ? "" : "?" + q) + ": " + reason);
        }
    }

    int skipped() {
        return skipped;
    }

    int expired() {
        return expired;
    }

    int failed() {
        return failed;
    }

    /** Pages that were read but held no usable posting (and were not expired). */
    int bad() {
        return bad;
    }

    int robotsSkipped() {
        return robotsSkipped;
    }

    /**
     * Fetches and reads one link. Returns null (and counts it skipped) when there is no usable
     * posting. Throws FetchBlockedException, or the last failure after too many in a row.
     */
    JobPosting process(URI link, Instant updatedAt) {
        return process(link, link, updatedAt);
    }

    /** As above, with the fetch address kept apart from the public url the posting is stored under. */
    JobPosting process(URI fetchUri, URI link, Instant updatedAt) {
        FetchException failure = null;
        boolean requested = true;
        JobPosting posting = null;
        boolean lastExpired = false;
        FetchedPage page = null;
        try {
            page = fetch.apply(fetchUri);
        } catch (RobotsDisallowedException e) {
            log.debug("Recipe '{}': {} disallowed by robots.txt", sourceName, link);
            requested = false;
            robotsSkipped++;
            why(link, "disallowed by robots.txt");
        } catch (FetchBlockedException e) {
            throw e;
        } catch (FetchException e) {
            log.warn("Recipe '{}': detail {} failed: {}", sourceName, link, e.getMessage());
            failure = e;
            failed++;
            why(link, "fetch failed (transport or server error)");
        }
        if (requested) {
            detailFetches++;
        }
        if (failure != null) {
            if (++failStreak >= MAX_CONSECUTIVE_DETAIL_FAILURES) {
                throw failure;
            }
        } else if (requested) {
            failStreak = 0;
        }
        if (page != null && page.status() / 100 == 2) {
            DetailExtractor.Result r = reader.read(page, link, updatedAt);
            posting = r.posting();
            if (posting == null && !r.expired()) {
                why(link, r.reason() == null ? "no usable posting" : r.reason());
            }
            if (r.expired()) {
                lastExpired = true;
                expired++;
            }
        }
        if (page != null && page.status() / 100 != 2) {
            why(link, "fetch failed with status " + page.status());
        }
        if (posting == null) {
            skipped++;
            if (requested && failure == null && !lastExpired) {
                bad++;
            }
        }
        return posting;
    }

    /** Charset from the Content-Type header, or null so jsoup sniffs BOM / meta and falls back to UTF-8. */
    static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher m = CHARSET_PARAM.matcher(contentType);
        if (!m.find()) {
            return null;
        }
        try {
            return Charset.forName(m.group(1)).name();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
