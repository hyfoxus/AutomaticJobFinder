package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;

/**
 * One run's worth of detail fetching, shared by both strategies: fetches a link, hands the page to
 * the extractor and keeps the counters. Five consecutive failed fetches abort the run; a blocked
 * response propagates at once.
 */
final class DetailStep {

    private static final Logger log = LoggerFactory.getLogger(DetailStep.class);
    static final int MAX_CONSECUTIVE_DETAIL_FAILURES = 5;
    private static final Pattern CHARSET_PARAM = Pattern.compile("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", Pattern.CASE_INSENSITIVE);

    private final PoliteFetcher fetcher;
    private final String sourceName;
    private final DetailExtractor extractor;
    private final int cap;
    private int detailFetches;
    private int failStreak;
    private int skipped;
    private int expired;
    private int failed;
    private int bad;
    private int robotsSkipped;

    DetailStep(PoliteFetcher fetcher, String sourceName, DetailExtractor extractor, int cap) {
        this.fetcher = fetcher;
        this.sourceName = sourceName;
        this.extractor = extractor;
        this.cap = cap;
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
        FetchException failure = null;
        boolean requested = true;
        JobPosting posting = null;
        boolean lastExpired = false;
        FetchedPage page = null;
        try {
            page = fetcher.fetch(link);
        } catch (RobotsDisallowedException e) {
            log.debug("Recipe '{}': {} disallowed by robots.txt", sourceName, link);
            requested = false;
            robotsSkipped++;
        } catch (FetchBlockedException e) {
            throw e;
        } catch (FetchException e) {
            log.warn("Recipe '{}': detail {} failed: {}", sourceName, link, e.getMessage());
            failure = e;
            failed++;
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
            Document doc = null;
            try {
                doc = Jsoup.parse(new ByteArrayInputStream(page.body()), charsetOf(page.contentType()),
                        page.finalUri().toString());
            } catch (IOException e) {
                // unusable page
            }
            if (doc != null) {
                DetailExtractor.Result r = extractor.extract(doc, link, updatedAt);
                posting = r.posting();
                if (r.expired()) {
                    lastExpired = true;
                    expired++;
                }
            }
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
