package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.source.JobSource;

/**
 * The sitemap strategy: list job URLs from a sitemap, read new pages and pages whose lastmod
 * changed. Every listed URL that is already stored is reported as seen, whatever happens to its
 * page, so it stays alive and is retried on the next run.
 */
public class SitemapRecipeJobSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(SitemapRecipeJobSource.class);

    private final Recipe recipe;
    private final PoliteFetcher fetcher;
    private final SitemapReader reader;
    private final Clock clock;
    private final Duration maxRun;
    private final Pattern urlPattern;
    private final Pattern idPattern;

    public SitemapRecipeJobSource(Recipe recipe, PoliteFetcher fetcher, SitemapReader reader, Clock clock,
            Duration maxRun) {
        this.recipe = recipe;
        this.fetcher = fetcher;
        this.reader = reader;
        this.clock = clock;
        this.maxRun = maxRun;
        this.urlPattern = Pattern.compile(recipe.sitemap().urlPattern());
        String idFrom = recipe.detail().idFrom();
        this.idPattern = idFrom != null && idFrom.startsWith(Recipe.ID_URL_REGEX_PREFIX)
                ? Pattern.compile(idFrom.substring(Recipe.ID_URL_REGEX_PREFIX.length())) : null;
    }

    @Override
    public String name() {
        return recipe.name();
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant deadline = clock.instant().plus(maxRun);
        Recipe.Sitemap sitemap = recipe.sitemap();
        SitemapReader.Result listed = reader.read(URI.create(sitemap.url()), urlPattern, sitemap.maxUrls());
        List<String> notes = new ArrayList<>(listed.notes());

        Map<String, Instant> known = context.knownJobs();
        Set<String> unchanged = new LinkedHashSet<>();
        List<SitemapReader.Entry> fresh = new ArrayList<>();
        List<SitemapReader.Entry> changed = new ArrayList<>();
        int index = 0;
        for (SitemapReader.Entry entry : listed.entries()) {
            String url = entry.url().toString();
            boolean withinCap = index++ < listed.fetchCount();
            if (!known.containsKey(url)) {
                if (withinCap) {
                    fresh.add(entry);
                }
                continue;
            }
            unchanged.add(url);
            if (!withinCap) {
                continue;
            }
            Instant stored = known.get(url);
            Instant storedSeconds = stored == null ? null : stored.truncatedTo(ChronoUnit.SECONDS);
            if (entry.lastmod() != null && !entry.lastmod().equals(storedSeconds)) {
                changed.add(entry);
            }
        }
        // Newest changes first; the sort is stable, so equal marks keep document order.
        changed.sort(Comparator.comparing(SitemapReader.Entry::lastmod).reversed());
        List<SitemapReader.Entry> queue = new ArrayList<>(fresh);
        queue.addAll(changed);

        DetailStep step = new DetailStep(fetcher, name(), DetailStep.extractorFor(recipe, idPattern),
                recipe.limits().maxDetailFetchesPerRun());
        List<JobPosting> postings = new ArrayList<>();
        int notVisitedNew = 0;
        int notVisitedKnown = 0;
        boolean outOfTime = false;
        for (SitemapReader.Entry entry : queue) {
            boolean isNew = !known.containsKey(entry.url().toString());
            if (!outOfTime && !step.capReached() && !clock.instant().isBefore(deadline)) {
                outOfTime = true;
            }
            if (outOfTime || step.capReached()) {
                if (isNew) {
                    notVisitedNew++;
                } else {
                    notVisitedKnown++;
                }
                continue;
            }
            JobPosting posting = step.process(entry.url(), entry.lastmod());
            if (posting != null) {
                postings.add(posting);
            }
        }

        if (outOfTime) {
            notes.add("Stopped early: run time budget of " + maxRun + " reached");
        }
        if (notVisitedNew + notVisitedKnown > 0) {
            notes.add("Not visited because of the " + (outOfTime ? "time budget" : "limit of "
                    + recipe.limits().maxDetailFetchesPerRun() + " detail fetches per run") + ": " + notVisitedNew
                    + " new, " + notVisitedKnown + " known (retried next run)");
        }
        if (step.bad() > 0) {
            notes.add(step.bad() + " bad page(s) skipped");
        }
        if (step.expired() > 0) {
            notes.add(step.expired() + " expired page(s) not stored");
        }
        if (step.failed() > 0) {
            notes.add(step.failed() + " page fetch(es) failed");
        }
        if (step.robotsSkipped() > 0) {
            notes.add(step.robotsSkipped() + " page(s) disallowed by robots.txt");
        }
        log.info("Recipe '{}': collected={} unchanged={} skipped={} detailFetches={}", name(), postings.size(),
                unchanged.size(), step.skipped(), step.detailFetches());
        return new FetchResult(postings, step.skipped(), unchanged, notes.isEmpty() ? null : String.join("; ", notes));
    }
}
