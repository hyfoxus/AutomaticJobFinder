package com.gnemirko.jobapplier.ingestion.learner.source;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.util.Text;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.JsonApiRecipeJobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.RecipeJobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapReader;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapRecipeJobSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Tries a proposed recipe on the real site in a small way: a clamped copy of it (at most 2 listing pages, 5 detail
 * fetches) through the normal engine and the polite fetcher. It passes when at least 3 postings have a title and a url.
 */
@Slf4j
@Component
@LearnerEnabled
public class SampleRunner {

    public static final int MIN_GOOD = 3;
    public static final int SAMPLE_PAGES = 2;
    public static final int SAMPLE_DETAILS = 5;
    public static final int SAMPLE_SITEMAP_URLS = 20;
    public static final int SAMPLE_API_ITEMS = 20;
    static final int MAX_TITLES = 10;

    /** @param ran false when the run itself failed (fetch or transport error), as opposed to finding too few postings
     * @param notes remarks for the report, including the bound of a sitemap read */
    public record Sample(boolean ran, boolean passed, int good, List<String> titles, List<String> notes,
            int matched, List<String> ids) {

        public Sample(boolean ran, boolean passed, int good, List<String> titles, List<String> notes) {
            this(ran, passed, good, titles, notes, -1, List.of());
        }
    }

    private final PoliteFetcher fetcher;
    private final Clock clock;
    private final Duration maxRun;
    private final java.net.URI apiBase;

    @Autowired
    public SampleRunner(PoliteFetcher fetcher, Clock clock, LearnerProperties props) {
        this(fetcher, clock, props.sampleMaxRun());
    }

    SampleRunner(PoliteFetcher fetcher, Clock clock, Duration maxRun) {
        this(fetcher, clock, maxRun, null);
    }

    /** Tests point json-api recipes at an http stub through {@code apiBase}. */
    SampleRunner(PoliteFetcher fetcher, Clock clock, Duration maxRun, java.net.URI apiBase) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxRun = maxRun;
        this.apiBase = apiBase;
    }

    /** A blocked response ({@link FetchBlockedException}) is not caught: the run must stop. */
    public Sample run(Recipe recipe) {
        List<String> notes = new ArrayList<>();
        java.util.function.Supplier<List<String>> diagnostics = List::of;
        java.util.function.IntSupplier matched = () -> -1;
        if (!Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())
                && !Recipe.STRATEGY_PAGINATED_LISTING.equals(recipe.strategy())
                && !Recipe.STRATEGY_JSON_API.equals(recipe.strategy())) {
            throw new IllegalArgumentException("no sample run for strategy " + recipe.strategy());
        }
        Recipe copy = clampedCopy(recipe);
        FetchResult result;
        try {
            JobSource source;
            if (Recipe.STRATEGY_JSON_API.equals(recipe.strategy())) {
                source = new JsonApiRecipeJobSource(copy, fetcher, clock, maxRun, apiBase);
            } else if (Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())) {
                SitemapRecipeJobSource s = new SitemapRecipeJobSource(copy, fetcher, new SitemapReader(fetcher), clock, maxRun);
                diagnostics = s::diagnostics;
                matched = s::matched;
                source = s;
            } else {
                RecipeJobSource s = new RecipeJobSource(copy, fetcher, clock, maxRun);
                diagnostics = s::diagnostics;
                source = s;
            }
            if (Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())) {
                notes.add("sitemap sample: at most " + SAMPLE_SITEMAP_URLS + " matching URLs are kept, but the engine still reads"
                        + " the sitemap and up to " + SitemapReader.MAX_CHILDREN + " child sitemap files in full");
            }
            result = source.fetch();
        } catch (FetchBlockedException e) {
            throw e;
        } catch (RuntimeException e) {
            notes.add("sample run failed: " + Text.clean(e.getMessage(), 300));
            return new Sample(false, false, 0, List.of(), notes, matched.getAsInt(), List.of());
        }
        for (String d : diagnostics.get()) {
            notes.add("sample: " + Text.clean(d, 300));
        }
        if (result.note() != null) {
            notes.add(Text.clean(result.note(), 300));
        }
        List<String> titles = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int good = 0;
        for (JobPosting p : result.postings()) {
            if (p.title() != null && !p.title().isBlank() && p.url() != null && !p.url().isBlank()) {
                good++;
                if (p.externalId() != null) {
                    ids.add(p.externalId());
                }
                if (titles.size() < MAX_TITLES) {
                    titles.add(Text.clean(p.title(), 120));
                }
            }
        }
        notes.add("sample fetched " + result.postings().size() + " postings, " + good + " with title and url");
        return new Sample(true, good >= MIN_GOOD, good, titles, notes, matched.getAsInt(), ids);
    }

    static Recipe clampedCopy(Recipe r) {
        Recipe.Limits limits = new Recipe.Limits(Math.min(SAMPLE_DETAILS, r.limits().maxDetailFetchesPerRun()));
        Recipe.Listing l = r.listing();
        if (l != null) {
            l = new Recipe.Listing(l.urlTemplate(), l.firstPage(), Math.min(SAMPLE_PAGES, l.maxPages()),
                    l.linkSelector(), l.linkPattern());
        }
        Recipe.Sitemap s = r.sitemap();
        if (s != null) {
            s = new Recipe.Sitemap(s.url(), s.urlPattern(), Math.min(SAMPLE_SITEMAP_URLS, s.maxUrls()), s.keepQuery());
        }
        Recipe.JsonApi a = r.jsonApi();
        if (a != null && a.paging() != null) {
            Recipe.Paging pg = a.paging();
            a = new Recipe.JsonApi(a.url(), a.method(), a.query(), a.bodyTemplate(),
                    new Recipe.Paging(pg.kind(), pg.start(), pg.size(), Math.min(SAMPLE_PAGES, pg.maxPages()),
                            pg.totalPath()),
                    a.itemsPath(), a.fields(), a.jobUrlTemplate(), Math.min(SAMPLE_API_ITEMS, a.maxItems()));
        }
        return new Recipe(r.name(), false, r.strategy(), l, s, r.workday(), r.detail(), limits, a);
    }
}
