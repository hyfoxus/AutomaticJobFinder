package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.util.UrlCanonicalizer;
import lombok.extern.slf4j.Slf4j;

/**
 * The paginated-listing strategy: walk listing pages, collect detail links, read each new
 * detail page's JobPosting JSON-LD. All requests go through the polite fetcher; a blocked
 * response propagates as FetchBlockedException and fails the run.
 */
@Slf4j
public class RecipeJobSource implements JobSource {

    private final Recipe recipe;
    private final PoliteFetcher fetcher;
    private final Clock clock;
    private final Duration maxRun;
    private final Pattern linkPattern;
    private final String linkSelector;
    private final boolean keepQuery;
    private final Pattern idPattern;
    private final String recipeHost;
    private final String recipeScheme;
    private boolean timeoutWarned;

    private static final long MATCH_LIMIT_NANOS = Duration.ofMillis(100).toNanos();

    public RecipeJobSource(Recipe recipe, PoliteFetcher fetcher, Clock clock, Duration maxRun) {
        this.recipe = recipe;
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxRun = maxRun;
        this.linkPattern = Pattern.compile(recipe.listing().linkPattern());
        this.linkSelector = recipe.listing().linkSelector();
        String lp = recipe.listing().linkPattern();
        this.keepQuery = lp.contains("\\?") || lp.contains("[?]") || lp.contains("=");
        String idFrom = recipe.detail().idFrom();
        this.idPattern = idFrom != null && idFrom.startsWith(Recipe.ID_URL_REGEX_PREFIX)
                ? Pattern.compile(idFrom.substring(Recipe.ID_URL_REGEX_PREFIX.length())) : null;
        URI first = UrlCanonicalizer.canonicalize(URI.create(template(recipe.listing().firstPage())), false);
        this.recipeHost = UrlCanonicalizer.hostOf(first);
        this.recipeScheme = first.getScheme().toLowerCase(java.util.Locale.ROOT);
    }

    private volatile java.util.List<String> diagnostics = java.util.List.of();

    /** Why pages of the last fetch were skipped, plus counts; for the recipe learner's report only. */
    public java.util.List<String> diagnostics() {
        return diagnostics;
    }

    @Override
    public String name() {
        return recipe.name();
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant deadline = clock.instant().plus(maxRun);
        Recipe.Listing listing = recipe.listing();
        int cap = recipe.limits().maxDetailFetchesPerRun();
        Set<String> seen = new HashSet<>();
        Set<String> unchanged = new LinkedHashSet<>();
        List<JobPosting> postings = new ArrayList<>();
        String note = null;
        timeoutWarned = false;
        DetailStep step = new DetailStep(fetcher, name(), DetailStep.extractorFor(recipe, idPattern), cap);

        pages:
        for (int page = listing.firstPage(); page < listing.firstPage() + listing.maxPages(); page++) {
            if (outOfTime(deadline)) {
                note = timeNote();
                break;
            }
            URI listingUri = URI.create(template(page));
            FetchedPage listingPage;
            try {
                listingPage = fetcher.fetch(listingUri);
            } catch (RobotsDisallowedException e) {
                log.warn("Recipe '{}': listing {} disallowed by robots.txt; stopping", name(), listingUri);
                note = "Listing " + listingUri + " disallowed by robots.txt";
                break;
            }
            if (listingPage.status() == 404 || listingPage.status() == 410) {
                if (page == listing.firstPage()) {
                    throw new FetchException("Listing " + listingUri + " returned " + listingPage.status());
                }
                break;
            }
            if (listingPage.status() / 100 != 2) {
                throw new FetchException("Listing " + listingUri + " returned " + listingPage.status());
            }
            List<URI> newLinks = newLinks(listingPage, seen);
            if (newLinks.isEmpty()) {
                break;
            }
            for (URI link : newLinks) {
                String url = link.toString();
                if (context.knownUrls().contains(url)) {
                    unchanged.add(url);
                    continue;
                }
                if (step.capReached()) {
                    continue;
                }
                if (outOfTime(deadline)) {
                    note = timeNote();
                    break pages;
                }
                JobPosting posting = step.process(link, null);
                if (posting != null) {
                    postings.add(posting);
                }
            }
        }
        diagnostics = step.skipReasons();
        log.info("Recipe '{}': collected={} unchanged={} skipped={} detailFetches={}", name(), postings.size(),
                unchanged.size(), step.skipped(), step.detailFetches());
        return new FetchResult(postings, step.skipped(), unchanged, note);
    }

    private List<URI> newLinks(FetchedPage page, Set<String> seen) {
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(page.body()), null, page.finalUri().toString());
        } catch (IOException e) {
            throw new FetchException("Cannot parse listing " + page.finalUri(), e);
        }
        List<URI> result = new ArrayList<>();
        for (Element a : doc.select(linkSelector)) {
            String href = a.attr("href");
            if (href.length() > UrlCanonicalizer.MAX_HREF) {
                href = href.substring(0, UrlCanonicalizer.MAX_HREF);
            }
            URI link = UrlCanonicalizer.canonicalize(href, page.finalUri(), keepQuery);
            if (link == null || !recipeHost.equals(UrlCanonicalizer.hostOf(link))
                    || !recipeScheme.equalsIgnoreCase(link.getScheme())) {
                continue;
            }
            if (!find(linkPattern, link.toString())) {
                continue;
            }
            if (seen.add(link.toString())) {
                result.add(link);
            }
        }
        return result;
    }

    private boolean find(Pattern pattern, String value) {
        try {
            return pattern.matcher(TimeLimitedCharSequence.limited(value, MATCH_LIMIT_NANOS)).find();
        } catch (TimeLimitedCharSequence.MatchTimeoutException e) {
            warnTimeout();
            return false;
        }
    }

    private void warnTimeout() {
        if (!timeoutWarned) {
            timeoutWarned = true;
            log.warn("Recipe '{}': a regex match timed out and was treated as no match; check the recipe patterns", name());
        }
    }

    private boolean outOfTime(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private String timeNote() {
        return "Stopped early: run time budget of " + maxRun + " reached";
    }

    private String template(int page) {
        return recipe.listing().urlTemplate().replace("{page}", Integer.toString(page));
    }
}
