package com.gnemirko.jobapplier.ingestion.learner.source;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    static final int MAX_TITLES = 10;

    /** @param ran false when the run itself failed (fetch or transport error), as opposed to finding too few postings
     * @param notes remarks for the report, including the bound of a sitemap read */
    public record Sample(boolean ran, boolean passed, int good, List<String> titles, List<String> notes) {
    }

    private final PoliteFetcher fetcher;
    private final Clock clock;
    private final Duration maxRun;

    public SampleRunner(PoliteFetcher fetcher, Clock clock, LearnerProperties props) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxRun = props.sampleMaxRun();
    }

    /** A blocked response ({@link FetchBlockedException}) is not caught: the run must stop. */
    public Sample run(Recipe recipe) {
        List<String> notes = new ArrayList<>();
        Recipe copy = clampedCopy(recipe);
        FetchResult result;
        try {
            JobSource source = Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())
                    ? new SitemapRecipeJobSource(copy, fetcher, new SitemapReader(fetcher), clock, maxRun)
                    : new RecipeJobSource(copy, fetcher, clock, maxRun);
            if (Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())) {
                notes.add("sitemap sample: at most " + SAMPLE_SITEMAP_URLS + " matching URLs are kept, but the engine still reads"
                        + " the sitemap and up to " + SitemapReader.MAX_CHILDREN + " child sitemap files in full");
            }
            result = source.fetch();
        } catch (FetchBlockedException e) {
            throw e;
        } catch (RuntimeException e) {
            notes.add("sample run failed: " + Text.clean(e.getMessage(), 300));
            return new Sample(false, false, 0, List.of(), notes);
        }
        if (result.note() != null) {
            notes.add(Text.clean(result.note(), 300));
        }
        List<String> titles = new ArrayList<>();
        int good = 0;
        for (JobPosting p : result.postings()) {
            if (p.title() != null && !p.title().isBlank() && p.url() != null && !p.url().isBlank()) {
                good++;
                if (titles.size() < MAX_TITLES) {
                    titles.add(Text.clean(p.title(), 120));
                }
            }
        }
        notes.add("sample fetched " + result.postings().size() + " postings, " + good + " with title and url");
        return new Sample(true, good >= MIN_GOOD, good, titles, notes);
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
            s = new Recipe.Sitemap(s.url(), s.urlPattern(), Math.min(SAMPLE_SITEMAP_URLS, s.maxUrls()));
        }
        return new Recipe(r.name(), false, r.strategy(), l, s, r.workday(), r.detail(), limits);
    }
}
